/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package kafka.api

import java.util.Properties
import java.util.concurrent.ExecutionException
import kafka.api.GroupAuthorizerIntegrationTest._
import kafka.security.authorizer.AclAuthorizer
import kafka.server.{BaseRequestTest, KafkaConfig}
import kafka.utils.TestUtils
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.acl.{AccessControlEntry, AclOperation, AclPermissionType}
import org.apache.kafka.common.config.internals.BrokerSecurityConfigs
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.resource.{PatternType, Resource, ResourcePattern, ResourceType}
import org.apache.kafka.common.security.auth.{AuthenticationContext, KafkaPrincipal}
import org.apache.kafka.common.security.authenticator.DefaultKafkaPrincipalBuilder
import org.apache.kafka.coordinator.transaction.TransactionLogConfigs
import org.apache.kafka.metadata.authorizer.StandardAuthorizer
import org.apache.kafka.security.authorizer.AclEntry.WILDCARD_HOST
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeEach, TestInfo}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import scala.jdk.CollectionConverters._

object GroupAuthorizerIntegrationTest {
  val BrokerPrincipal = new KafkaPrincipal("Group", "broker")
  val ClientPrincipal = new KafkaPrincipal("Group", "client")

  val BrokerListenerName = "BROKER"
  val ClientListenerName = "CLIENT"
  val ControllerListenerName = "CONTROLLER"

  class GroupPrincipalBuilder extends DefaultKafkaPrincipalBuilder(null, null) {
    override def build(context: AuthenticationContext): KafkaPrincipal = {
      context.listenerName match {
        case BrokerListenerName | ControllerListenerName => BrokerPrincipal
        case ClientListenerName => ClientPrincipal
        case listenerName => throw new IllegalArgumentException(s"No principal mapped to listener $listenerName")
      }
    }
  }
}

class GroupAuthorizerIntegrationTest extends BaseRequestTest {

  val brokerId: Integer = 0

  override def brokerCount: Int = 1
  override def interBrokerListenerName: ListenerName = new ListenerName(BrokerListenerName)
  override def listenerName: ListenerName = new ListenerName(ClientListenerName)

  def brokerPrincipal: KafkaPrincipal = BrokerPrincipal
  def clientPrincipal: KafkaPrincipal = ClientPrincipal

  override def kraftControllerConfigs(): collection.Seq[Properties] = {
    val controllerConfigs = super.kraftControllerConfigs()
    controllerConfigs.foreach(addNodeProperties)
    controllerConfigs
  }

  override def brokerPropertyOverrides(properties: Properties): Unit = {
    properties.put(KafkaConfig.BrokerIdProp, brokerId.toString)
    addNodeProperties(properties)
  }

  private def addNodeProperties(properties: Properties): Unit = {
    if (isKRaftTest()) {
      properties.put(KafkaConfig.AuthorizerClassNameProp, classOf[StandardAuthorizer].getName)
      properties.put(StandardAuthorizer.SUPER_USERS_CONFIG, BrokerPrincipal.toString)
    } else {
      properties.put(KafkaConfig.AuthorizerClassNameProp, classOf[AclAuthorizer].getName)
    }

    properties.put(KafkaConfig.OffsetsTopicPartitionsProp, "1")
    properties.put(KafkaConfig.OffsetsTopicReplicationFactorProp, "1")
    properties.put(TransactionLogConfigs.TRANSACTIONS_TOPIC_PARTITIONS_CONFIG, "1")
    properties.put(TransactionLogConfigs.TRANSACTIONS_TOPIC_REPLICATION_FACTOR_CONFIG, "1")
    properties.put(TransactionLogConfigs.TRANSACTIONS_TOPIC_MIN_ISR_CONFIG, "1")
    properties.put(BrokerSecurityConfigs.PRINCIPAL_BUILDER_CLASS_CONFIG, classOf[GroupPrincipalBuilder].getName)
  }

  @BeforeEach
  override def setUp(testInfo: TestInfo): Unit = {
    doSetup(testInfo, createOffsetsTopic = false)

    // Allow inter-broker communication
    addAndVerifyAcls(
      Set(createAcl(AclOperation.CLUSTER_ACTION, AclPermissionType.ALLOW, principal = BrokerPrincipal)),
      new ResourcePattern(ResourceType.CLUSTER, Resource.CLUSTER_NAME, PatternType.LITERAL)
    )

    createOffsetsTopic(interBrokerListenerName)
  }

  private def createAcl(aclOperation: AclOperation,
                        aclPermissionType: AclPermissionType,
                        principal: KafkaPrincipal = ClientPrincipal): AccessControlEntry = {
    new AccessControlEntry(principal.toString, WILDCARD_HOST, aclOperation, aclPermissionType)
  }

  @ParameterizedTest
  @ValueSource(strings = Array("zk", "kraft"))
  def testUnauthorizedProduceAndConsume(quorum: String): Unit = {
    val topic = "topic"
    val topicPartition = new TopicPartition("topic", 0)

    createTopic(topic, listenerName = interBrokerListenerName)

    val producer = createProducer()
    val produceException = assertThrows(classOf[ExecutionException],
      () => producer.send(new ProducerRecord[Array[Byte], Array[Byte]](topic, "message".getBytes)).get()).getCause
    assertTrue(produceException.isInstanceOf[TopicAuthorizationException])
    assertEquals(Set(topic), produceException.asInstanceOf[TopicAuthorizationException].unauthorizedTopics.asScala)

    val consumer = createConsumer(configsToRemove = List(ConsumerConfig.GROUP_ID_CONFIG))
    consumer.assign(List(topicPartition).asJava)
    val consumeException = assertThrows(classOf[TopicAuthorizationException],
      () => TestUtils.pollUntilAtLeastNumRecords(consumer, numRecords = 1))
    assertEquals(Set(topic), consumeException.unauthorizedTopics.asScala)
  }

  @ParameterizedTest
  @ValueSource(strings = Array("zk", "kraft"))
  def testAuthorizedProduceAndConsume(quorum: String): Unit = {
    val topic = "topic"
    val topicPartition = new TopicPartition("topic", 0)

    createTopic(topic, listenerName = interBrokerListenerName)

    addAndVerifyAcls(
      Set(createAcl(AclOperation.WRITE, AclPermissionType.ALLOW)),
      new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL)
    )
    val producer = createProducer()
    producer.send(new ProducerRecord[Array[Byte], Array[Byte]](topic, "message".getBytes)).get()

    addAndVerifyAcls(
      Set(createAcl(AclOperation.READ, AclPermissionType.ALLOW)),
      new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL)
    )
    val consumer = createConsumer(configsToRemove = List(ConsumerConfig.GROUP_ID_CONFIG))
    consumer.assign(List(topicPartition).asJava)
    TestUtils.pollUntilAtLeastNumRecords(consumer, numRecords = 1)
  }

}

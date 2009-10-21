/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.jms;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;

import org.apache.camel.Component;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.PollingConsumer;
import org.apache.camel.Processor;
import org.apache.camel.component.jms.requestor.Requestor;
import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.HeaderFilterStrategyAware;
import org.apache.camel.spi.ManagementAware;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A <a href="http://activemq.apache.org/jms.html">JMS Endpoint</a>
 *
 * @version $Revision:520964 $
 */
@ManagedResource(description = "Managed JMS Endpoint")
public class JmsEndpoint extends DefaultEndpoint implements HeaderFilterStrategyAware, ManagementAware<JmsEndpoint> {
    private HeaderFilterStrategy headerFilterStrategy;
    private boolean pubSubDomain;
    private JmsBinding binding;
    private String destinationName;
    private Destination destination;
    private String selector;
    private JmsConfiguration configuration;
    private Requestor requestor;

    public JmsEndpoint() {
        this(null, null);
    }

    public JmsEndpoint(Topic destination) throws JMSException {
        this("jms:topic:" + destination.getTopicName(), null);
        this.destination = destination;
    }

    public JmsEndpoint(String uri, JmsComponent component, String destinationName, boolean pubSubDomain, JmsConfiguration configuration) {
        super(uri, component);
        this.configuration = configuration;
        this.destinationName = destinationName;
        this.pubSubDomain = pubSubDomain;
    }

    public JmsEndpoint(String endpointUri, JmsBinding binding, JmsConfiguration configuration, String destinationName, boolean pubSubDomain) {
        super(endpointUri);
        this.binding = binding;
        this.configuration = configuration;
        this.destinationName = destinationName;
        this.pubSubDomain = pubSubDomain;
    }

    public JmsEndpoint(String endpointUri, String destinationName, boolean pubSubDomain) {
        this(endpointUri, null, new JmsConfiguration(), destinationName, pubSubDomain);
        this.binding = new JmsBinding(this);
    }

    /**
     * Creates a pub-sub endpoint with the given destination
     */
    public JmsEndpoint(String endpointUri, String destinationName) {
        this(endpointUri, destinationName, true);
    }


    /**
     * Returns a new JMS endpoint for the given JMS destination using the configuration from the given JMS component
     */
    public static JmsEndpoint newInstance(Destination destination, JmsComponent component) throws JMSException {
        JmsEndpoint answer = newInstance(destination);
        JmsConfiguration newConfiguration = component.getConfiguration().copy();
        answer.setConfiguration(newConfiguration);
        answer.setCamelContext(component.getCamelContext());
        return answer;
    }

    /**
     * Returns a new JMS endpoint for the given JMS destination
     */
    public static JmsEndpoint newInstance(Destination destination) throws JMSException {
        if (destination instanceof TemporaryQueue) {
            return new JmsTemporaryQueueEndpoint((TemporaryQueue) destination);
        }
        if (destination instanceof TemporaryTopic) {
            return new JmsTemporaryTopicEndpoint((TemporaryTopic) destination);
        }
        if (destination instanceof Queue) {
            return new JmsQueueEndpoint((Queue) destination);
        } else {
            return new JmsEndpoint((Topic) destination);
        }
    }

    public JmsProducer createProducer() throws Exception {
        return new JmsProducer(this);
    }

    public JmsConsumer createConsumer(Processor processor) throws Exception {
        AbstractMessageListenerContainer listenerContainer = configuration.createMessageListenerContainer(this);
        return createConsumer(processor, listenerContainer);
    }

    public AbstractMessageListenerContainer createMessageListenerContainer() {
        return configuration.createMessageListenerContainer(this);
    }

    public void configureListenerContainer(AbstractMessageListenerContainer listenerContainer) {
        if (destinationName != null) {
            listenerContainer.setDestinationName(destinationName);
        } else if (destination != null) {
            listenerContainer.setDestination(destination);
        } else {
            DestinationResolver resolver = getDestinationResolver();
            if (resolver != null) {
                listenerContainer.setDestinationResolver(resolver);
            } else {
                throw new IllegalArgumentException("Neither destination, destinationName or destinationResolver are specified on this endpoint!");
            }
        }
        listenerContainer.setPubSubDomain(pubSubDomain);
    }

    /**
     * Creates a consumer using the given processor and listener container
     *
     * @param processor         the processor to use to process the messages
     * @param listenerContainer the listener container
     * @return a newly created consumer
     * @throws Exception if the consumer cannot be created
     */
    public JmsConsumer createConsumer(Processor processor, AbstractMessageListenerContainer listenerContainer) throws Exception {
        configureListenerContainer(listenerContainer);
        return new JmsConsumer(this, processor, listenerContainer);
    }

    @Override
    public PollingConsumer createPollingConsumer() throws Exception {
        JmsOperations template = createInOnlyTemplate();
        return new JmsPollingConsumer(this, template);
    }

    @Override
    public Exchange createExchange(ExchangePattern pattern) {
        Exchange exchange = new DefaultExchange(this, pattern);
        exchange.setProperty(Exchange.BINDING, getBinding());
        return exchange;
    }

    public Exchange createExchange(Message message) {
        Exchange exchange = createExchange(getExchangePattern());
        exchange.setIn(new JmsMessage(message, getBinding()));
        return exchange;
    }

    /**
     * Factory method for creating a new template for InOnly message exchanges
     */
    public JmsOperations createInOnlyTemplate() {
        return configuration.createInOnlyTemplate(this, pubSubDomain, destinationName);
    }

    /**
     * Factory method for creating a new template for InOut message exchanges
     */
    public JmsOperations createInOutTemplate() {
        return configuration.createInOutTemplate(this, pubSubDomain, destinationName, configuration.getRequestTimeout());
    }

    public Object getManagedObject(JmsEndpoint endpoint) {
        return this;
    }

    // Properties
    // -------------------------------------------------------------------------
    public HeaderFilterStrategy getHeaderFilterStrategy() {
        if (headerFilterStrategy == null) {
            headerFilterStrategy = new JmsHeaderFilterStrategy();
        }
        return headerFilterStrategy;
    }

    public void setHeaderFilterStrategy(HeaderFilterStrategy strategy) {
        this.headerFilterStrategy = strategy;
    }

    public JmsBinding getBinding() {
        if (binding == null) {
            binding = new JmsBinding(this);
        }
        return binding;
    }

    /**
     * Sets the binding used to convert from a Camel message to and from a JMS
     * message
     *
     * @param binding the binding to use
     */
    public void setBinding(JmsBinding binding) {
        this.binding = binding;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

    public Destination getDestination() {
        return destination;
    }

    /**
     * Allows a specific JMS Destination object to be used as the destination
     */
    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    public JmsConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JmsConfiguration configuration) {
        this.configuration = configuration;
    }

    public String getSelector() {
        return selector;
    }

    /**
     * Sets the JMS selector to use
     */
    public void setSelector(String selector) {
        this.selector = selector;
    }

    @ManagedAttribute
    public boolean isSingleton() {
        return true;
    }

    public synchronized Requestor getRequestor() throws Exception {
        if (requestor == null) {
            requestor = new Requestor(getConfiguration(), getScheduledExecutorService());
            requestor.start();
        }
        return requestor;
    }

    public void setRequestor(Requestor requestor) {
        this.requestor = requestor;
    }

    public boolean isPubSubDomain() {
        return pubSubDomain;
    }

    /**
     * Lazily loads the temporary queue type if one has not been explicitly configured
     * via calling the {@link JmsProviderMetadata#setTemporaryQueueType(Class)}
     * on the {@link #getConfiguration()} instance
     */
    public Class<? extends TemporaryQueue> getTemporaryQueueType() {
        JmsProviderMetadata metadata = getProviderMetadata();
        JmsOperations template = getMetadataJmsOperations();
        return metadata.getTemporaryQueueType(template);
    }

    /**
     * Lazily loads the temporary topic type if one has not been explicitly configured
     * via calling the {@link JmsProviderMetadata#setTemporaryTopicType(Class)}
     * on the {@link #getConfiguration()} instance
     */
    public Class<? extends TemporaryTopic> getTemporaryTopicType() {
        JmsOperations template = getMetadataJmsOperations();
        JmsProviderMetadata metadata = getProviderMetadata();
        return metadata.getTemporaryTopicType(template);
    }

    /**
     * Returns the provider metadata
     */
    protected JmsProviderMetadata getProviderMetadata() {
        JmsConfiguration conf = getConfiguration();
        JmsProviderMetadata metadata = conf.getProviderMetadata();
        return metadata;
    }


    /**
     * Returns the {@link JmsOperations} used for metadata operations such as creating temporary destinations
     */
    protected JmsOperations getMetadataJmsOperations() {
        JmsOperations template = getConfiguration().getMetadataJmsOperations(this);
        if (template == null) {
            throw new IllegalArgumentException("No Metadata JmsTemplate supplied!");
        }
        return template;
    }

    // Delegated properties from the configuration
    //-------------------------------------------------------------------------
    @ManagedAttribute
    public int getAcknowledgementMode() {
        return getConfiguration().getAcknowledgementMode();
    }

    @ManagedAttribute
    public String getAcknowledgementModeName() {
        return getConfiguration().getAcknowledgementModeName();
    }

    @ManagedAttribute
    public int getCacheLevel() {
        return getConfiguration().getCacheLevel();
    }

    @ManagedAttribute
    public String getCacheLevelName() {
        return getConfiguration().getCacheLevelName();
    }

    @ManagedAttribute
    public String getClientId() {
        return getConfiguration().getClientId();
    }

    @ManagedAttribute
    public int getConcurrentConsumers() {
        return getConfiguration().getConcurrentConsumers();
    }

    public ConnectionFactory getConnectionFactory() {
        return getConfiguration().getConnectionFactory();
    }

    public ConsumerType getConsumerType() {
        return getConfiguration().getConsumerType();
    }

    public DestinationResolver getDestinationResolver() {
        return getConfiguration().getDestinationResolver();
    }

    @ManagedAttribute
    public String getDurableSubscriptionName() {
        return getConfiguration().getDurableSubscriptionName();
    }

    public ExceptionListener getExceptionListener() {
        return getConfiguration().getExceptionListener();
    }

    @ManagedAttribute
    public int getIdleTaskExecutionLimit() {
        return getConfiguration().getIdleTaskExecutionLimit();
    }

    public JmsOperations getJmsOperations() {
        return getConfiguration().getJmsOperations();
    }

    public ConnectionFactory getListenerConnectionFactory() {
        return getConfiguration().getListenerConnectionFactory();
    }

    @ManagedAttribute
    public int getMaxConcurrentConsumers() {
        return getConfiguration().getMaxConcurrentConsumers();
    }

    @ManagedAttribute
    public int getMaxMessagesPerTask() {
        return getConfiguration().getMaxMessagesPerTask();
    }

    public MessageConverter getMessageConverter() {
        return getConfiguration().getMessageConverter();
    }

    public JmsOperations getMetadataJmsOperations(JmsEndpoint endpoint) {
        return getConfiguration().getMetadataJmsOperations(endpoint);
    }

    @ManagedAttribute
    public int getPriority() {
        return getConfiguration().getPriority();
    }

    @ManagedAttribute
    public long getReceiveTimeout() {
        return getConfiguration().getReceiveTimeout();
    }

    @ManagedAttribute
    public long getRecoveryInterval() {
        return getConfiguration().getRecoveryInterval();
    }

    @ManagedAttribute
    public String getReplyTo() {
        return getConfiguration().getReplyTo();
    }

    @ManagedAttribute
    public String getReplyToDestinationSelectorName() {
        return getConfiguration().getReplyToDestinationSelectorName();
    }

    public String getReplyToTempDestinationAffinity() {
        return getConfiguration().getReplyToTempDestinationAffinity();
    }

    public long getRequestMapPurgePollTimeMillis() {
        return getConfiguration().getRequestMapPurgePollTimeMillis();
    }

    @ManagedAttribute
    public long getRequestTimeout() {
        return getConfiguration().getRequestTimeout();
    }

    public TaskExecutor getTaskExecutor() {
        return getConfiguration().getTaskExecutor();
    }

    public ConnectionFactory getTemplateConnectionFactory() {
        return getConfiguration().getTemplateConnectionFactory();
    }

    @ManagedAttribute
    public long getTimeToLive() {
        return getConfiguration().getTimeToLive();
    }

    public PlatformTransactionManager getTransactionManager() {
        return getConfiguration().getTransactionManager();
    }

    @ManagedAttribute
    public String getTransactionName() {
        return getConfiguration().getTransactionName();
    }

    @ManagedAttribute
    public int getTransactionTimeout() {
        return getConfiguration().getTransactionTimeout();
    }

    @ManagedAttribute
    public boolean isAcceptMessagesWhileStopping() {
        return getConfiguration().isAcceptMessagesWhileStopping();
    }

    @ManagedAttribute
    public boolean isAlwaysCopyMessage() {
        return getConfiguration().isAlwaysCopyMessage();
    }

    @ManagedAttribute
    public boolean isAutoStartup() {
        return getConfiguration().isAutoStartup();
    }

    @ManagedAttribute
    public boolean isDeliveryPersistent() {
        return getConfiguration().isDeliveryPersistent();
    }

    @ManagedAttribute
    public boolean isDisableReplyTo() {
        return getConfiguration().isDisableReplyTo();
    }

    @ManagedAttribute
    public boolean isEagerLoadingOfProperties() {
        return getConfiguration().isEagerLoadingOfProperties();
    }

    @ManagedAttribute
    public boolean isExplicitQosEnabled() {
        return getConfiguration().isExplicitQosEnabled();
    }

    @ManagedAttribute
    public boolean isExposeListenerSession() {
        return getConfiguration().isExposeListenerSession();
    }

    @ManagedAttribute
    public boolean isMessageIdEnabled() {
        return getConfiguration().isMessageIdEnabled();
    }

    @ManagedAttribute
    public boolean isMessageTimestampEnabled() {
        return getConfiguration().isMessageTimestampEnabled();
    }

    @ManagedAttribute
    public boolean isPreserveMessageQos() {
        return getConfiguration().isPreserveMessageQos();
    }

    @ManagedAttribute
    public boolean isPubSubNoLocal() {
        return getConfiguration().isPubSubNoLocal();
    }

    @ManagedAttribute
    public boolean isReplyToDeliveryPersistent() {
        return getConfiguration().isReplyToDeliveryPersistent();
    }

    @ManagedAttribute
    public boolean isSubscriptionDurable() {
        return getConfiguration().isSubscriptionDurable();
    }

    @ManagedAttribute
    public boolean isTransacted() {
        return getConfiguration().isTransacted();
    }

    @ManagedAttribute
    public boolean isTransactedInOut() {
        return getConfiguration().isTransactedInOut();
    }

    @ManagedAttribute
    public boolean isUseMessageIDAsCorrelationID() {
        return getConfiguration().isUseMessageIDAsCorrelationID();
    }

    public boolean isUseVersion102() {
        return getConfiguration().isUseVersion102();
    }

    @ManagedAttribute
    public void setAcceptMessagesWhileStopping(boolean acceptMessagesWhileStopping) {
        getConfiguration().setAcceptMessagesWhileStopping(acceptMessagesWhileStopping);
    }

    @ManagedAttribute
    public void setAcknowledgementMode(int consumerAcknowledgementMode) {
        getConfiguration().setAcknowledgementMode(consumerAcknowledgementMode);
    }

    @ManagedAttribute
    public void setAcknowledgementModeName(String consumerAcknowledgementMode) {
        getConfiguration().setAcknowledgementModeName(consumerAcknowledgementMode);
    }

    @ManagedAttribute
    public void setAlwaysCopyMessage(boolean alwaysCopyMessage) {
        getConfiguration().setAlwaysCopyMessage(alwaysCopyMessage);
    }

    @ManagedAttribute
    public void setAutoStartup(boolean autoStartup) {
        getConfiguration().setAutoStartup(autoStartup);
    }

    @ManagedAttribute
    public void setCacheLevel(int cacheLevel) {
        getConfiguration().setCacheLevel(cacheLevel);
    }

    @ManagedAttribute
    public void setCacheLevelName(String cacheName) {
        getConfiguration().setCacheLevelName(cacheName);
    }

    @ManagedAttribute
    public void setClientId(String consumerClientId) {
        getConfiguration().setClientId(consumerClientId);
    }

    @ManagedAttribute
    public void setConcurrentConsumers(int concurrentConsumers) {
        getConfiguration().setConcurrentConsumers(concurrentConsumers);
    }

    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        getConfiguration().setConnectionFactory(connectionFactory);
    }

    public void setConsumerType(ConsumerType consumerType) {
        getConfiguration().setConsumerType(consumerType);
    }

    @ManagedAttribute
    public void setDeliveryPersistent(boolean deliveryPersistent) {
        getConfiguration().setDeliveryPersistent(deliveryPersistent);
    }

    public void setDestinationResolver(DestinationResolver destinationResolver) {
        getConfiguration().setDestinationResolver(destinationResolver);
    }

    @ManagedAttribute
    public void setDisableReplyTo(boolean disableReplyTo) {
        getConfiguration().setDisableReplyTo(disableReplyTo);
    }

    @ManagedAttribute
    public void setDurableSubscriptionName(String durableSubscriptionName) {
        getConfiguration().setDurableSubscriptionName(durableSubscriptionName);
    }

    @ManagedAttribute
    public void setEagerLoadingOfProperties(boolean eagerLoadingOfProperties) {
        getConfiguration().setEagerLoadingOfProperties(eagerLoadingOfProperties);
    }

    public void setExceptionListener(ExceptionListener exceptionListener) {
        getConfiguration().setExceptionListener(exceptionListener);
    }

    @ManagedAttribute
    public void setExplicitQosEnabled(boolean explicitQosEnabled) {
        getConfiguration().setExplicitQosEnabled(explicitQosEnabled);
    }

    @ManagedAttribute
    public void setExposeListenerSession(boolean exposeListenerSession) {
        getConfiguration().setExposeListenerSession(exposeListenerSession);
    }

    @ManagedAttribute
    public void setIdleTaskExecutionLimit(int idleTaskExecutionLimit) {
        getConfiguration().setIdleTaskExecutionLimit(idleTaskExecutionLimit);
    }

    public void setJmsOperations(JmsOperations jmsOperations) {
        getConfiguration().setJmsOperations(jmsOperations);
    }

    public void setListenerConnectionFactory(ConnectionFactory listenerConnectionFactory) {
        getConfiguration().setListenerConnectionFactory(listenerConnectionFactory);
    }

    @ManagedAttribute
    public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
        getConfiguration().setMaxConcurrentConsumers(maxConcurrentConsumers);
    }

    @ManagedAttribute
    public void setMaxMessagesPerTask(int maxMessagesPerTask) {
        getConfiguration().setMaxMessagesPerTask(maxMessagesPerTask);
    }

    public void setMessageConverter(MessageConverter messageConverter) {
        getConfiguration().setMessageConverter(messageConverter);
    }

    @ManagedAttribute
    public void setMessageIdEnabled(boolean messageIdEnabled) {
        getConfiguration().setMessageIdEnabled(messageIdEnabled);
    }

    @ManagedAttribute
    public void setMessageTimestampEnabled(boolean messageTimestampEnabled) {
        getConfiguration().setMessageTimestampEnabled(messageTimestampEnabled);
    }

    public void setMetadataJmsOperations(JmsOperations metadataJmsOperations) {
        getConfiguration().setMetadataJmsOperations(metadataJmsOperations);
    }

    @ManagedAttribute
    public void setPreserveMessageQos(boolean preserveMessageQos) {
        getConfiguration().setPreserveMessageQos(preserveMessageQos);
    }

    @ManagedAttribute
    public void setPriority(int priority) {
        getConfiguration().setPriority(priority);
    }

    public void setProviderMetadata(JmsProviderMetadata providerMetadata) {
        getConfiguration().setProviderMetadata(providerMetadata);
    }

    @ManagedAttribute
    public void setPubSubNoLocal(boolean pubSubNoLocal) {
        getConfiguration().setPubSubNoLocal(pubSubNoLocal);
    }

    @ManagedAttribute
    public void setReceiveTimeout(long receiveTimeout) {
        getConfiguration().setReceiveTimeout(receiveTimeout);
    }

    @ManagedAttribute
    public void setRecoveryInterval(long recoveryInterval) {
        getConfiguration().setRecoveryInterval(recoveryInterval);
    }

    @ManagedAttribute
    public void setReplyTo(String replyToDestination) {
        getConfiguration().setReplyTo(replyToDestination);
    }

    @ManagedAttribute
    public void setReplyToDeliveryPersistent(boolean replyToDeliveryPersistent) {
        getConfiguration().setReplyToDeliveryPersistent(replyToDeliveryPersistent);
    }

    @ManagedAttribute
    public void setReplyToDestinationSelectorName(String replyToDestinationSelectorName) {
        getConfiguration().setReplyToDestinationSelectorName(replyToDestinationSelectorName);
    }

    public void setReplyToTempDestinationAffinity(String replyToTempDestinationAffinity) {
        getConfiguration().setReplyToTempDestinationAffinity(replyToTempDestinationAffinity);
    }

    public void setRequestMapPurgePollTimeMillis(long requestMapPurgePollTimeMillis) {
        getConfiguration().setRequestMapPurgePollTimeMillis(requestMapPurgePollTimeMillis);
    }

    @ManagedAttribute
    public void setRequestTimeout(long requestTimeout) {
        getConfiguration().setRequestTimeout(requestTimeout);
    }

    @ManagedAttribute
    public void setSubscriptionDurable(boolean subscriptionDurable) {
        getConfiguration().setSubscriptionDurable(subscriptionDurable);
    }

    public void setTaskExecutor(TaskExecutor taskExecutor) {
        getConfiguration().setTaskExecutor(taskExecutor);
    }

    public void setTemplateConnectionFactory(ConnectionFactory templateConnectionFactory) {
        getConfiguration().setTemplateConnectionFactory(templateConnectionFactory);
    }

    @ManagedAttribute
    public void setTimeToLive(long timeToLive) {
        getConfiguration().setTimeToLive(timeToLive);
    }

    @ManagedAttribute
    public void setTransacted(boolean consumerTransacted) {
        getConfiguration().setTransacted(consumerTransacted);
    }

    @ManagedAttribute
    public void setTransactedInOut(boolean transactedInOut) {
        getConfiguration().setTransactedInOut(transactedInOut);
    }

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        getConfiguration().setTransactionManager(transactionManager);
    }

    @ManagedAttribute
    public void setTransactionName(String transactionName) {
        getConfiguration().setTransactionName(transactionName);
    }

    @ManagedAttribute
    public void setTransactionTimeout(int transactionTimeout) {
        getConfiguration().setTransactionTimeout(transactionTimeout);
    }

    @ManagedAttribute
    public void setUseMessageIDAsCorrelationID(boolean useMessageIDAsCorrelationID) {
        getConfiguration().setUseMessageIDAsCorrelationID(useMessageIDAsCorrelationID);
    }

    public void setUseVersion102(boolean useVersion102) {
        getConfiguration().setUseVersion102(useVersion102);
    }

    public JmsMessageType getJmsMessageType() {
        return getConfiguration().getJmsMessageType();
    }

    public void setJmsMessageType(JmsMessageType jmsMessageType) {
        getConfiguration().setJmsMessageType(jmsMessageType);
    }

    public JmsKeyFormatStrategy getJmsKeyFormatStrategy() {
        return getConfiguration().getJmsKeyFormatStrategy();
    }

    public void setJmsKeyFormatStrategy(JmsKeyFormatStrategy jmsHeaderStrategy) {
        getConfiguration().setJmsKeyFormatStrategy(jmsHeaderStrategy);
    }

    @ManagedAttribute
    public boolean isTransferExchange() {
        return getConfiguration().isTransferExchange();
    }

    @ManagedAttribute
    public void setTransferExchange(boolean transferExchange) {
        getConfiguration().setTransferExchange(transferExchange);
    }

    @ManagedAttribute
    public boolean isTransferException() {
        return getConfiguration().isTransferException();
    }

    @ManagedAttribute
    public void setTransferException(boolean transferException) {
        getConfiguration().setTransferException(transferException);
    }

    @ManagedAttribute(description = "Camel id")
    public String getCamelId() {
        return getCamelContext().getName();
    }

    @ManagedAttribute(description = "Endpoint Uri")
    @Override
    public String getEndpointUri() {
        return super.getEndpointUri();
    }

    // Implementation methods
    //-------------------------------------------------------------------------

    @Override
    protected String createEndpointUri() {
        String scheme = "jms";
        if (destination != null) {
            return scheme + ":" + destination;
        } else if (destinationName != null) {
            return scheme + ":" + destinationName;
        }
        DestinationResolver resolver = getDestinationResolver();
        if (resolver != null) {
            return scheme + ":" + resolver;
        }
        return super.createEndpointUri();
    }

}

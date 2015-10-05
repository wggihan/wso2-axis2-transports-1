/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.axis2.transport.rabbitmq.rpc;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownSignalException;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.rabbitmq.RabbitMQConnectionFactory;
import org.apache.axis2.transport.rabbitmq.RabbitMQMessage;
import org.apache.axis2.transport.rabbitmq.utils.AxisRabbitMQException;
import org.apache.axis2.transport.rabbitmq.utils.RabbitMQConstants;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;

/**
 * Class that performs the actual sending of a RabbitMQ AMQP message,
 */

public class RabbitMQRPCMessageSender {
    private static final Log log = LogFactory.getLog(RabbitMQRPCMessageSender.class);

    private RPCChannel rpcChannel = null;
    private String targetEPR = null;
    private Hashtable<String, String> properties;
    private String correlationId;

    /**
     * Create a RabbitMQSender using a ConnectionFactory and target EPR
     *
     * @param connectionFactory the RabbitMQ connection factory
     * @param targetEPR         the targetAddress
     * @param epProperties
     */
    //TODO : cache connection factories with targetEPR string. should include queue autodeclare properties etc..
    public RabbitMQRPCMessageSender(RabbitMQConnectionFactory connectionFactory, String targetEPR, Hashtable<String, String> epProperties) {

        this.targetEPR = targetEPR;

        try {
            rpcChannel = connectionFactory.getRPCChannel();
        } catch (InterruptedException e) {
            handleException("Error while getting RPC channel", e);
        }

        if (!this.targetEPR.startsWith(RabbitMQConstants.RABBITMQ_PREFIX)) {
            handleException("Invalid prefix for a AMQP EPR : " + targetEPR);
        } else {
            this.properties = epProperties;
//            this.properties = BaseUtils.getEPRProperties(targetEPR);
        }
    }

    public RabbitMQMessage send(RabbitMQMessage message, MessageContext msgContext) throws
            AxisRabbitMQException, IOException {

        publish(message, msgContext);
        processResponse(message.getCorrelationId(), message.getReplyTo(), BaseUtils.getEPRProperties(targetEPR));

        return message;
    }

    /**
     * Perform the creation of exchange/queue and the Outputstream
     *
     * @param message    the RabbitMQ AMQP message
     * @param msgContext the Axis2 MessageContext
     */
    private void publish(RabbitMQMessage message, MessageContext msgContext) throws
            AxisRabbitMQException, IOException {

        String exchangeName = null;
        AMQP.BasicProperties basicProperties = null;
        byte[] messageBody = null;

        if (rpcChannel.isOpen()) {
            String queueName = properties.get(RabbitMQConstants.QUEUE_NAME);
            String routeKey = properties
                    .get(RabbitMQConstants.QUEUE_ROUTING_KEY);
            exchangeName = properties.get(RabbitMQConstants.EXCHANGE_NAME);
            String exchangeType = properties
                    .get(RabbitMQConstants.EXCHANGE_TYPE);
            String replyTo = properties.get(RabbitMQConstants.REPLY_TO_NAME);
            String correlationID = properties.get(RabbitMQConstants.CORRELATION_ID);

//            String queueAutoDeclareStr = properties.get(RabbitMQConstants.QUEUE_AUTODECLARE);
//            String exchangeAutoDeclareStr = properties.get(RabbitMQConstants.EXCHANGE_AUTODECLARE);
//            boolean queueAutoDeclare = true;
//            boolean exchangeAutoDeclare = true;
//
//            if (!StringUtils.isEmpty(queueAutoDeclareStr)) {
//                queueAutoDeclare = Boolean.parseBoolean(queueAutoDeclareStr);
//            }
//
//            if (!StringUtils.isEmpty(exchangeAutoDeclareStr)) {
//                exchangeAutoDeclare = Boolean.parseBoolean(exchangeAutoDeclareStr);
//            }

            message.setReplyTo(replyTo);

            if ((!StringUtils.isEmpty(replyTo)) && (StringUtils.isEmpty(correlationID))) {
                //if reply-to is enabled a correlationID must be available. If not specified, use messageID
                correlationID = message.getMessageId();
            }

            if (!StringUtils.isEmpty(correlationID)) {
                correlationID += "-" + Thread.currentThread().getId();
                message.setCorrelationId(correlationID);
            }

            if (queueName == null || queueName.equals("")) {
                log.info("No queue name is specified");
            }

            if (routeKey == null && !"x-consistent-hash".equals(exchangeType)) {
                if (queueName == null || queueName.equals("")) {
                    log.info("Routing key is not specified");
                } else {
                    log.info(
                            "Routing key is not specified. Using queue name as the routing key.");
                    routeKey = queueName;
                }
            }

            Channel channel = rpcChannel.getChannel();

            //Declaring the queue
            //TODO : fix this with channel reuse logic
            /*
            if (queueAutoDeclare && queueName != null && !queueName.equals("")) {
                RabbitMQUtils.declareQueue(channel, queueName, properties);
            }

            //Declaring the exchange
            if (exchangeAutoDeclare && exchangeName != null && !exchangeName.equals("")) {
                RabbitMQUtils.declareExchange(channel, exchangeName, properties);

                if (queueName != null && !"x-consistent-hash".equals(exchangeType)) {
                    // Create bind between the queue and exchange with the routeKey
                    try {
                        if (!channel.isOpen()) {
                            channel = connection.createChannel();
                            log.debug("Channel is not open. Creating a new channel.");
                        }
                        channel.queueBind(queueName, exchangeName, routeKey);
                    } catch (IOException e) {
                        handleException(
                                "Error occurred while creating the bind between the queue: "
                                        + queueName + " & exchange: " + exchangeName + " with route-key " + routeKey, e);
                    }
                }
            }
*/
            AMQP.BasicProperties.Builder builder = buildBasicProperties(message);

            String deliveryModeString = properties
                    .get(RabbitMQConstants.QUEUE_DELIVERY_MODE);
            int deliveryMode = RabbitMQConstants.DEFAULT_DELIVERY_MODE;
            if (deliveryModeString != null) {
                deliveryMode = Integer.parseInt(deliveryModeString);
            }
            builder.deliveryMode(deliveryMode);
            builder.replyTo(replyTo);
            builder.correlationId(message.getCorrelationId());

            basicProperties = builder.build();
            OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
            MessageFormatter messageFormatter = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                messageFormatter = MessageProcessorSelector.getMessageFormatter(msgContext);
            } catch (AxisFault axisFault) {
                throw new AxisRabbitMQException(
                        "Unable to get the message formatter to use",
                        axisFault);
            }

            //server plugging should be enabled before using x-consistent hashing
            //for x-consistent-hashing only exchangeName, exchangeType and routingKey should be
            // given. Queue/exchange creation, bindings should be done at the broker
            try {
                // generate random value as routeKey if the exchangeType is
                // x-consistent-hash type
                if (exchangeType != null
                        && exchangeType.equals("x-consistent-hash")) {
                    routeKey = UUID.randomUUID().toString();
                }

            } catch (UnsupportedCharsetException ex) {
                handleException(
                        "Unsupported encoding "
                                + format.getCharSetEncoding(), ex);
            }
            try {
                messageFormatter.writeTo(msgContext, format, out, false);
                messageBody = out.toByteArray();
            } catch (IOException e) {
                handleException("IO Error while creating BytesMessage", e);
            } finally {
                if (out != null) {
                    out.close();
                }
            }

            //if (connection != null) {
            try {
//                    if ((channel == null) || !channel.isOpen()) {
//                        channel = connection.createChannel();
//                        log.debug("Channel is not open or unavailable. Creating a new channel.");
//                    }
                if (exchangeName != null && exchangeName != "") {
                    channel.basicPublish(exchangeName, routeKey, basicProperties,
                            messageBody);
                } else {
                    channel.basicPublish("", routeKey, basicProperties,
                            messageBody);
                }
            } catch (IOException e) {
                handleException("Error while publishing the message", e);
            }
            //}

//            if (channel != null) {
//                channel.close();
//            }
        } else {
            handleException("Channel cannot be created");
        }
    }


    private RabbitMQMessage processResponse(String correlationID, String replyTo, Hashtable<String, String> eprProperties) throws IOException {

//        String queueAutoDeclareStr = eprProperties.get(RabbitMQConstants.QUEUE_AUTODECLARE);
//        boolean queueAutoDeclare = true;

//        if (!StringUtils.isEmpty(queueAutoDeclareStr)) {
//            queueAutoDeclare = Boolean.parseBoolean(queueAutoDeclareStr);
//        }

       /*
        if (queueAutoDeclare && !RabbitMQUtils.isQueueAvailable(connection, channel, replyTo)) {

            log.info("Reply-to queue : " + replyTo + " not available, hence creating a new one");
            RabbitMQUtils.declareQueue(connection, channel, replyTo, eprProperties);
        }*/

        int timeout = RabbitMQConstants.DEFAULT_REPLY_TO_TIMEOUT;
        String timeoutStr = eprProperties.get(RabbitMQConstants.REPLY_TO_TIMEOUT);
        if (!StringUtils.isEmpty(timeoutStr)) {
            try {
                timeout = Integer.parseInt(timeoutStr);
            } catch (NumberFormatException e) {
                log.warn("Number format error in reading replyto timeout value. Proceeding with default value (30000ms)", e);
            }
        }

        QueueingConsumer consumer = rpcChannel.getConsumer();
        QueueingConsumer.Delivery delivery = null;
        RabbitMQMessage message = new RabbitMQMessage();
        Channel channel = rpcChannel.getChannel();
        System.out.println("REPLYTO QUEUE:" + rpcChannel.getReplyToQueue());
        System.out.println("CORRELATIONID:" + correlationID);
        boolean responseFound = false;


//
//        //start consuming without acknowledging
//        String consumerTag = channel.basicConsume(replyTo, false, consumer);

        try {
            while (!responseFound) {
                log.debug("Waiting for next delivery from reply to queue " + replyTo);
                delivery = consumer.nextDelivery(timeout);
                if (delivery != null) {
                    if (!StringUtils.isEmpty(delivery.getProperties().getCorrelationId())) {
                        if (delivery.getProperties().getCorrelationId().equals(correlationID)) {
                            responseFound = true;
                            log.debug("Found matching response with correlation ID : " + correlationID + ". Sending ack");
                            //acknowledge correct message
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        } else {
                            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                        }
                    }
                }
            }
        } catch (ShutdownSignalException e) {
            log.error("Error receiving message from RabbitMQ broker " + e.getLocalizedMessage());
        } catch (InterruptedException e) {
            log.error("Error receiving message from RabbitMQ broker " + e.getLocalizedMessage());
        } catch (ConsumerCancelledException e) {
            log.error("Error receiving message from RabbitMQ broker" + e.getLocalizedMessage());
        } finally {
//            if (channel != null || channel.isOpen()) {
//                //stop consuming
//                channel.basicCancel(consumerTag); //TODO : stop consuming?
//                channel.close();
//            }

        }

        if (delivery != null) {
            log.debug("Processing response from reply-to queue");
            AMQP.BasicProperties properties = delivery.getProperties();
            Map<String, Object> headers = properties.getHeaders();
            message.setBody(delivery.getBody());
            message.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
            message.setReplyTo(properties.getReplyTo());
            message.setMessageId(properties.getMessageId());

            //get content type from message
            String contentType = properties.getContentType();
            if (contentType == null) {
                //if not get content type from transport parameter
                contentType = eprProperties.get(RabbitMQConstants.REPLY_TO_CONTENT_TYPE);
                if (contentType == null) {
                    //if none is given, set to default content type
                    log.warn("Setting default content type " + RabbitMQConstants.DEFAULT_CONTENT_TYPE);
                    contentType = RabbitMQConstants.DEFAULT_CONTENT_TYPE;
                }
            }
            message.setContentType(contentType);
            message.setContentEncoding(properties.getContentEncoding());
            message.setCorrelationId(properties.getCorrelationId());

            if (headers != null) {
                message.setHeaders(headers);
                if (headers.get(RabbitMQConstants.SOAP_ACTION) != null) {
                    message.setSoapAction(headers.get(
                            RabbitMQConstants.SOAP_ACTION).toString());
                }
            }
        }
        return message;
    }


    /**
     * Build and populate the AMQP.BasicProperties using the RabbitMQMessage
     *
     * @param message the RabbitMQMessage to be used to get the properties
     * @return AMQP.BasicProperties object
     */
    private AMQP.BasicProperties.Builder buildBasicProperties(RabbitMQMessage message) {
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties().builder();
        builder.messageId(message.getMessageId());
        builder.contentType(message.getContentType());
        builder.replyTo(message.getReplyTo());
        builder.correlationId(message.getCorrelationId());
        builder.contentEncoding(message.getContentEncoding());
        Map<String, Object> headers = message.getHeaders();
        headers.put(RabbitMQConstants.SOAP_ACTION, message.getSoapAction());
        builder.headers(headers);
        return builder;
    }

    private void handleException(String s) {
        log.error(s);
        throw new AxisRabbitMQException(s);
    }

    private void handleException(String message, Exception e) {
        log.error(message, e);
        throw new AxisRabbitMQException(message, e);
    }

}
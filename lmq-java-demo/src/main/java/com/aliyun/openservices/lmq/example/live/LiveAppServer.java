package com.aliyun.openservices.lmq.example.live;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson.JSONObject;

import com.aliyun.openservices.lmq.example.demo.TokenApiDemo;
import com.aliyun.openservices.ons.api.Action;
import com.aliyun.openservices.ons.api.ConsumeContext;
import com.aliyun.openservices.ons.api.Consumer;
import com.aliyun.openservices.ons.api.Message;
import com.aliyun.openservices.ons.api.MessageListener;
import com.aliyun.openservices.ons.api.ONSFactory;
import com.aliyun.openservices.ons.api.Producer;
import com.aliyun.openservices.ons.api.PropertyKeyConst;
import com.aliyun.openservices.ons.api.SendResult;
import com.aliyuncs.utils.StringUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * @author
 * @date 2020/5/10
 */
public class LiveAppServer {
    private Charset utf8Charset = Charset.forName("utf-8");
    private final String AccessKey = "xxx";
    private final String SecretKey = "xxx";
    private final String GroupId = "xxx";
    private final String NameAddr = "xxx";
    private final String mqttInstanceId = "xxx";
    private final String regionId = "xxx";

    private final String MqttSecondTopic = "mqttSecondTopic";
    private final String MsgTypeMessage = "message";
    private final String MsgTypeCommand = "command";
    private final String RoomId = "roomId";
    private final String CommandType = "type";
    private final String ShutUpCommand = "shutUp";

    private String subTopic = "roomSend";
    private String pubTopic = "room";
    private String mqttSecondTopicMessage = "message";
    private String mqttSecondTopicP2p = "p2p";
    private String mqttSecondTopicStatus = "status";
    private String mqttSecondTopicSys = "system";

    private Map<String, Boolean> shutUpClientMap = new ConcurrentHashMap<>(8);
    private Consumer consumer;
    private Producer producer;

    private void start() throws IOException {
        consumer.start();
        producer.start();

        HttpServer loginServer = HttpServer.create(new InetSocketAddress(8081), 0);
        loginServer.createContext("/login", new LoginHandler());
        loginServer.start();
    }

    public static void main(String[] args) throws IOException {
        LiveAppServer liveAppServer = new LiveAppServer();
        liveAppServer.initOnsConsumer();
        liveAppServer.initOnsProducer();
        liveAppServer.start();
    }

    class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            // todo ????????????????????????
            try {
                String token = TokenApiDemo.applyToken(AccessKey, SecretKey,
                    Arrays.asList(subTopic + "/#", pubTopic + "/#"),
                    "R,W", 3600 * 1000L, mqttInstanceId, regionId);
                httpExchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                httpExchange.sendResponseHeaders(200, 0);
                OutputStream os = httpExchange.getResponseBody();
                os.write(token.getBytes(utf8Charset));
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void processMsg(Message message) {
        try {
            String mqttSecondTopic = message.getUserProperties(MqttSecondTopic);
            if (mqttSecondTopic.contains(MsgTypeMessage)) {
                sendMessageToMqtt(message.getBody());
            } else if (mqttSecondTopic.contains(MsgTypeCommand)) {
                sendCommandToMqtt(message.getBody());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMessageToMqtt(byte[] body) {
        // todo ????????????
        String message = new String(body, Charset.forName("utf-8"));
        JSONObject jsonObject = JSONObject.parseObject(message);
        Long roomId = jsonObject.getLong(RoomId);
        String peer = jsonObject.getString("peer");
        if (peer != null && Boolean.FALSE.equals(shutUpClientMap.get(peer))) {
            //????????????????????????
            return;
        }
        if (roomId != null) {
            Message onsMessage = new Message();
            onsMessage.setTopic(pubTopic);
            onsMessage.putUserProperties(MqttSecondTopic, mqttSecondTopicMessage + "/" + roomId);
            onsMessage.setBody(body);
            if (rateLimit()) {
                SendResult sendResult = producer.send(onsMessage);
                System.out.println(sendResult.getMessageId());
            }
        }
    }

    private void sendCommandToMqtt(byte[] body) {
        // todo ????????????
        String command = new String(body, utf8Charset);
        JSONObject jsonObject = JSONObject.parseObject(command);
        if (ShutUpCommand.equals(jsonObject.getString(CommandType))) {
            String peer = jsonObject.getString("peer");
            if (!StringUtils.isEmpty(peer)) {
                shutUpClientMap.put(peer, false);
                JSONObject toPeer = new JSONObject();
                toPeer.put("type", ShutUpCommand);
                Message onsMessage = new Message();
                onsMessage.setTopic(pubTopic);
                onsMessage.putUserProperties(MqttSecondTopic, mqttSecondTopicP2p + "/" + peer);
                onsMessage.setBody(toPeer.toJSONString().getBytes(utf8Charset));
                if (rateLimit()) {
                    SendResult sendResult = producer.send(onsMessage);
                    System.out.println(sendResult.getMessageId());
                }
            }
        }
    }

    private void sendSysMessageToMqtt(byte[] sysMessage) {
        Message onsMessage = new Message();
        onsMessage.setTopic(pubTopic);
        onsMessage.putUserProperties(MqttSecondTopic, mqttSecondTopicSys);
        onsMessage.setBody(sysMessage);
        if (rateLimit()) {
            SendResult sendResult = producer.send(onsMessage);
            System.out.println(sendResult.getMessageId());
        }
    }

    private void processStatus(Message statusMessage) {
        // ???????????????https://help.aliyun.com/document_detail/50069.html?spm=a2c4g.11186623.6.556.7ec41e7bCK6jaz

        // ???????????????????????????com.aliyun.openservices.lmq.example.demo.MQTTClientStatusNoticeProcessDemo

        // ????????????????????????MQTT?????????, ????????????????????????????????????????????????????????????????????????
        Message onsMessage = new Message();
        onsMessage.setTopic(pubTopic);
        onsMessage.putUserProperties(MqttSecondTopic, mqttSecondTopicStatus);
        onsMessage.setBody(statusMessage.getBody());
        if (rateLimit()) {
            producer.send(onsMessage);
        }
    }

    private boolean rateLimit() {
        // todo ????????????????????????????????????????????????????????????????????????????????????10???

        return true;
    }

    public void initOnsProducer() {
        /**
         * ????????????????????? for RocketMQ ?????????????????????????????????????????????????????????????????????
         */
        Properties properties = new Properties();

        /**
         * ?????? RocketMQ ???????????? GroupID?????????????????? groupId ??? MQ4IoT ???????????? GroupId ???2??????????????????????????????????????????????????????
         */
        properties.setProperty(PropertyKeyConst.GROUP_ID, GroupId);
        /**
         * ?????? accesskey?????????????????????????????????
         */
        properties.put(PropertyKeyConst.AccessKey, AccessKey);
        /**
         * ?????? secretKey??????????????????????????????????????????Signature???????????????????????????
         */
        properties.put(PropertyKeyConst.SecretKey, SecretKey);
        /**
         * ?????? TCP ????????????
         */
        properties.put(PropertyKeyConst.NAMESRV_ADDR, NameAddr);

        producer = ONSFactory.createProducer(properties);
        producer.start();
    }

    public void initOnsConsumer() {
        /**
         * ????????????????????? for RocketMQ ?????????????????????????????????????????????????????????????????????
         */
        Properties properties = new Properties();
        /**
         * ?????? RocketMQ ???????????? GroupID?????????????????? groupId ??? MQ4IoT ???????????? GroupId ???2??????????????????????????????????????????????????????
         */
        properties.setProperty(PropertyKeyConst.GROUP_ID, GroupId);
        /**
         * ?????? accesskey?????????????????????????????????
         */
        properties.put(PropertyKeyConst.AccessKey, AccessKey);
        /**
         * ?????? secretKey??????????????????????????????????????????Signature???????????????????????????
         */
        properties.put(PropertyKeyConst.SecretKey, SecretKey);
        /**
         * ?????? TCP ????????????
         */
        properties.put(PropertyKeyConst.NAMESRV_ADDR, NameAddr);

        String teacherStatusTopic = "GID_teacher_MQTT";
        String studentStatusTopic = "GID_student_MQTT";

        consumer = ONSFactory.createConsumer(properties);
        consumer.subscribe(subTopic, "*", new MsgListener());

        /**
         *  ???????????????????????????????????????????????????????????? connect ????????? tcpclean ????????????
         */
        consumer.subscribe(teacherStatusTopic, "connect||tcpclean", new StatusListener());
        consumer.subscribe(studentStatusTopic, "connect||tcpclean", new StatusListener());

        consumer.start();
    }

    class MsgListener implements MessageListener {

        @Override
        public Action consume(Message message, ConsumeContext context) {
            try {
                processMsg(message);
                return Action.CommitMessage;
            } catch (Exception e) {
                e.printStackTrace();
                return Action.ReconsumeLater;
            }
        }
    }

    class StatusListener implements MessageListener {

        @Override
        public Action consume(Message message, ConsumeContext context) {
            try {
                processStatus(message);
                return Action.CommitMessage;
            } catch (Exception e) {
                e.printStackTrace();
                return Action.ReconsumeLater;
            }
        }
    }

}

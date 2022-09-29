package cn.wildfirechat.push.ios;

import cn.wildfirechat.push.PushMessage;
import cn.wildfirechat.push.PushMessageType;
import cn.wildfirechat.push.Utility;
import com.turo.pushy.apns.*;
import com.turo.pushy.apns.metrics.micrometer.MicrometerApnsClientMetricsListener;
import com.turo.pushy.apns.util.ApnsPayloadBuilder;
import com.turo.pushy.apns.util.SimpleApnsPushNotification;
import com.turo.pushy.apns.util.concurrent.PushNotificationFuture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;

import static java.lang.System.exit;

@Component
public class ApnsServer  {
    private static final Logger LOG = LoggerFactory.getLogger(ApnsServer.class);

    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final MicrometerApnsClientMetricsListener productMetricsListener =
            new MicrometerApnsClientMetricsListener(meterRegistry,
                    "notifications", "apns_product");
    final MicrometerApnsClientMetricsListener developMetricsListener =
            new MicrometerApnsClientMetricsListener(meterRegistry,
                    "notifications", "apns_develop");

    ApnsClient productSvc;
    ApnsClient developSvc;
    ApnsClient productVoipSvc;
    ApnsClient developVoipSvc;

    @Autowired
    private ApnsConfig mConfig;

    @PostConstruct
    private void init() {
        if (StringUtils.isEmpty(mConfig.alert)) {
            mConfig.alert = "default";
        }

        if (StringUtils.isEmpty(mConfig.voipAlert)) {
            mConfig.alert = "default";
        }

        try {
            productSvc = new ApnsClientBuilder()
                    .setApnsServer(ApnsClientBuilder.PRODUCTION_APNS_HOST)
                    .setClientCredentials(new File(mConfig.cerPath), mConfig.cerPwd)
                    .setMetricsListener(productMetricsListener)
                    .build();

            developSvc = new ApnsClientBuilder()
                    .setApnsServer(ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
                    .setClientCredentials(new File(mConfig.cerPath), mConfig.cerPwd)
                    .setMetricsListener(developMetricsListener)
                    .build();

            if (mConfig.voipFeature) {
                productVoipSvc = new ApnsClientBuilder()
                        .setApnsServer(ApnsClientBuilder.PRODUCTION_APNS_HOST)
                        .setClientCredentials(new File(mConfig.voipCerPath), mConfig.voipCerPwd)
                        .setMetricsListener(productMetricsListener)
                        .build();
                developVoipSvc = new ApnsClientBuilder()
                        .setApnsServer(ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
                        .setClientCredentials(new File(mConfig.voipCerPath), mConfig.voipCerPwd)
                        .setMetricsListener(developMetricsListener)
                        .build();
            }

        } catch (IOException e) {
            e.printStackTrace();
            exit(-1);
        }
    }

    public long getMessageId(PushMessage pushMessage) {
        try {
            JSONObject jsonObject = (JSONObject)(new JSONParser().parse(pushMessage.pushData));
            if(jsonObject.get("messageUid") instanceof Long) {
                return (Long)jsonObject.get("messageUid");
            } else if(jsonObject.get("messageUid") instanceof Integer) {
                return (Integer)jsonObject.get("messageUid");
            } else if(jsonObject.get("messageUid") instanceof Double) {
                double uid = (Double)jsonObject.get("messageUid");
                return (long)uid;
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void pushMessage(PushMessage pushMessage) {
        ApnsClient service;
        String sound = mConfig.alert;

        String collapseId = null;
        if(pushMessage.messageId > 0) {
            collapseId = pushMessage.messageId + "";
        }

        if (pushMessage.pushMessageType == PushMessageType.PUSH_MESSAGE_TYPE_VOIP_INVITE) {
            sound = mConfig.voipAlert;
        } else if(pushMessage.pushMessageType == PushMessageType.PUSH_MESSAGE_TYPE_VOIP_BYE || pushMessage.pushMessageType == PushMessageType.PUSH_MESSAGE_TYPE_VOIP_ANSWER) {
            sound = null;
        } else if(pushMessage.pushMessageType == PushMessageType.PUSH_MESSAGE_TYPE_RECALLED || pushMessage.pushMessageType == PushMessageType.PUSH_MESSAGE_TYPE_DELETED) {
            sound = null;
            long messageId = getMessageId(pushMessage);
            if(messageId > 0) {
                collapseId = messageId + "";
            }
        } else if(pushMessage.pushMessageType != PushMessageType.PUSH_MESSAGE_TYPE_NORMAL && pushMessage.pushMessageType != PushMessageType.PUSH_MESSAGE_TYPE_SECRET_CHAT) {
            LOG.error("not support push message type:{}", pushMessage.pushMessageType);
        }

        int badge = pushMessage.getUnReceivedMsg();
        if (badge <= 0) {
            badge = 1;
        }

        String[] arr = Utility.getPushTitleAndContent(pushMessage);
        String title = arr[0];
        String body = arr[1];

        final ApnsPayloadBuilder payloadBuilder = new ApnsPayloadBuilder();
        payloadBuilder.setAlertBody(body);
        payloadBuilder.setAlertTitle(title);
        payloadBuilder.setBadgeNumber(badge);
        payloadBuilder.setSound(sound);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("sender", pushMessage.sender);
        jsonObject.put("convType", pushMessage.convType);
        jsonObject.put("convTarget", pushMessage.target);
        jsonObject.put("convLine", pushMessage.line);
        jsonObject.put("contType", pushMessage.cntType);
        jsonObject.put("pushData", pushMessage.pushData);
        payloadBuilder.addCustomProperty("wfc", jsonObject);

        Calendar c = Calendar.getInstance();
        ApnsPushNotification pushNotification;

        if (!mConfig.voipFeature || pushMessage.pushMessageType != PushMessageType.PUSH_MESSAGE_TYPE_VOIP_INVITE) {
            if (pushMessage.getPushType() == IOSPushType.IOS_PUSH_TYPE_DISTRIBUTION) {
                service = productSvc;
            } else {
                service = developSvc;
            }
            if(pushMessage.pushMessageType != PushMessageType.PUSH_MESSAGE_TYPE_VOIP_INVITE || StringUtils.isEmpty(pushMessage.getVoipDeviceToken())) {
                c.add(Calendar.MINUTE, 10); //普通推送
                String payload = payloadBuilder.buildWithDefaultMaximumLength();
                pushNotification = new SimpleApnsPushNotification(pushMessage.deviceToken, pushMessage.packageName, payload, c.getTime(), DeliveryPriority.CONSERVE_POWER, PushType.ALERT, collapseId);
            } else {
                c.add(Calendar.MINUTE, 1); //voip通知，使用普通推送
                payloadBuilder.setContentAvailable(true);
                payloadBuilder.addCustomProperty("voip", true);
                payloadBuilder.addCustomProperty("voip_type", pushMessage.pushMessageType);
                payloadBuilder.addCustomProperty("voip_data", pushMessage.pushData);
                String payload = payloadBuilder.buildWithDefaultMaximumLength();
                pushNotification = new SimpleApnsPushNotification(pushMessage.deviceToken, pushMessage.packageName, payload, c.getTime(), DeliveryPriority.IMMEDIATE, PushType.BACKGROUND, collapseId);
            }
        } else {
            if (pushMessage.getPushType() == IOSPushType.IOS_PUSH_TYPE_DISTRIBUTION) {
                service = productVoipSvc;
            } else {
                service = developVoipSvc;
            }
            c.add(Calendar.MINUTE, 1);
            String payload = payloadBuilder.buildWithDefaultMaximumLength();
            pushNotification = new SimpleApnsPushNotification(pushMessage.voipDeviceToken, pushMessage.packageName + ".voip", payload, c.getTime(), DeliveryPriority.IMMEDIATE, PushType.VOIP, collapseId);
        }

        SimpleApnsPushNotification simpleApnsPushNotification = (SimpleApnsPushNotification)pushNotification;
        LOG.info("CollapseId:{}", simpleApnsPushNotification.getCollapseId());

        if (service == null) {
            LOG.error("Service not exist!!!!");
            return;
        }

        final PushNotificationFuture<ApnsPushNotification, PushNotificationResponse<ApnsPushNotification>> sendNotificationFuture = service.sendNotification(pushNotification);
        sendNotificationFuture.addListener(future -> {
            // When using a listener, callers should check for a failure to send a
            // notification by checking whether the future itself was successful
            // since an exception will not be thrown.
            if (future.isSuccess()) {
                final PushNotificationResponse<ApnsPushNotification> pushNotificationResponse =
                        sendNotificationFuture.getNow();
                if(!pushNotificationResponse.isAccepted()) {
                    LOG.error("apns push failure: {}", pushNotificationResponse.getRejectionReason());
                } else {
                    LOG.info("push success: {}", pushNotificationResponse.getApnsId().toString());
                    LOG.info("token invalidate timestamp: {}", pushNotificationResponse.getTokenInvalidationTimestamp());
                }
            } else {
                // Something went wrong when trying to send the notification to the
                // APNs gateway. We can find the exception that caused the failure
                // by getting future.cause().
                future.cause().printStackTrace();
                LOG.error("apns push failure: {}", future.cause().getLocalizedMessage());
            }
        });
    }
}

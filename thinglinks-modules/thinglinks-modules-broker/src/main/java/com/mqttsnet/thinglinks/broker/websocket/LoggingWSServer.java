package com.mqttsnet.thinglinks.broker.websocket;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;

import com.mqttsnet.thinglinks.common.core.utils.ErrorUtil;
import com.mqttsnet.thinglinks.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * WebSocket获取实时日志并输出到Web页面
 */
@Slf4j
@Component
@ServerEndpoint(value = "/websocket/logging", configurator = MyEndpointConfigure.class)
public class LoggingWSServer {

    @Autowired
    AsyncTaskExecutor asyncTaskExecutor;

    /**
     * 连接集合
     */
    private static Map<String, Session> sessionMap = new ConcurrentHashMap<>(16);
    private static Map<String, Integer> lengthMap = new ConcurrentHashMap<>(16);

    /**
     * 匹配日期开头加换行，2019-08-12 14:15:04
     */
    private Pattern datePattern = Pattern.compile("[\\d+][\\d+][\\d+][\\d+]-[\\d+][\\d+]-[\\d+][\\d+] [\\d+][\\d+]:[\\d+][\\d+]:[\\d+][\\d+]");


    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session) {
        //添加到集合中
        sessionMap.put(session.getId(), session);
        lengthMap.put(session.getId(), 1);//默认从第一行开始

        //获取日志信息
        asyncTaskExecutor.submit(() -> {
            boolean first = true;
            BufferedReader reader = null;
            FileReader fileReader = null;
            while (sessionMap.get(session.getId()) != null) {
                try {
                    //日志文件，获取最新的如 thinglinks-broker.2022-07-12.log
                    fileReader = new FileReader(System.getProperty("user.dir") + "/logs/thinglinks-broker/all" + "." + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) +
                            ".log");
                    //字符流
                    reader = new BufferedReader(fileReader);
                    Object[] lines = reader.lines().toArray();

                    //只取从上次之后产生的日志
                    Object[] copyOfRange = Arrays.copyOfRange(lines, lengthMap.get(session.getId()), lines.length);

                    //对日志进行着色，更加美观  PS：注意，这里要根据日志生成规则来操作
                    for (int i = 0; i < copyOfRange.length; i++) {
                        String line = String.valueOf(copyOfRange[i]);
                        copyOfRange[i] = line;
                    }

                    //存储最新一行开始
                    lengthMap.replace(session.getId(), lines.length);

                    //第一次如果太大，截取最新的200行就够了，避免传输的数据太大
                    if (first && copyOfRange.length > 200) {
                        copyOfRange = Arrays.copyOfRange(copyOfRange, copyOfRange.length - 200, copyOfRange.length);
                        first = false;
                    }

                    String result = StringUtils.join(copyOfRange, "<br/>");

                    //发送
                    send(session, result);

                    //休眠一秒
                    Thread.sleep(1000);
                } catch (Exception e) {
                    //输出到日志文件中
                    log.error(ErrorUtil.errorInfoToString(e));
                }
            }
            try {
                reader.close();
                fileReader.close();
            } catch (IOException e) {
                //输出到日志文件中
                log.error(ErrorUtil.errorInfoToString(e));
            }
        });
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose(Session session) {
        //从集合中删除
        sessionMap.remove(session.getId());
        lengthMap.remove(session.getId());
    }

    /**
     * 发生错误时调用
     */
    @OnError
    public void onError(Session session, Throwable error) {
        //输出到日志文件中
        log.error(ErrorUtil.errorInfoToString(error));
    }

    /**
     * 服务器接收到客户端消息时调用的方法
     */
    @OnMessage
    public void onMessage(String message, Session session) {

    }

    /**
     * 封装一个send方法，发送消息到前端
     */
    private void send(Session session, String message) {
        try {
            session.getBasicRemote().sendText(message);
        } catch (Exception e) {
            //输出到日志文件中
            log.error(ErrorUtil.errorInfoToString(e));
        }
    }
}

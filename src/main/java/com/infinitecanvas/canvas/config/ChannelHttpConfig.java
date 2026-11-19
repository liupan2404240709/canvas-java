package com.infinitecanvas.canvas.config;

import com.infinitecanvas.canvas.infra.channel.ChannelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 渠道探测用的 HTTP 客户端。
 *
 * <p>为什么不能复用 {@code promptRestClient}：两者的超时策略是<b>相反</b>的。
 * 提示词同步是后台任务，可以容忍 20 秒；渠道测试是用户点一下在等结果，
 * 15 秒已经是耐心的上限。共用一个 bean 就必须取交集，等于让后台任务迁就前台。
 *
 * <p>为什么不能直接用 Spring 自动配置的那个 {@code RestClient.Builder} 的默认值：
 * 默认<b>没有超时</b>（HttpURLConnection 的 connectTimeout=0 表示无限等待）。
 * 一个填错的域名 + 对方防火墙丢包 = 请求线程永久挂住，几个人就能把 Tomcat 的
 * 200 个线程耗光。
 *
 * <p>状态码处理：和 {@code promptRestClient} 一样用"谓词恒 false"关掉默认的异常抛出。
 * 401/404 在这里不是"异常"，而是<b>需要翻译成用户能看懂的一句话</b>的正常分支，
 * 混进异常通道会让日志噪音变大、还得额外区分业务异常和框架异常。
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelHttpConfig {

    @Bean
    public RestClient channelRestClient(RestClient.Builder builder, ChannelProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        return builder
                .requestFactory(factory)
                .defaultStatusHandler(status -> false, (request, response) -> {
                })
                .build();
    }
}

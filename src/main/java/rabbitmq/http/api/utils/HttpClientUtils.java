package rabbitmq.http.api.utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultHttpResponseParserFactory;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLException;


public class HttpClientUtils {


    static PoolingHttpClientConnectionManager manager = null;
    static CloseableHttpClient httpClient = null;

    public static synchronized CloseableHttpClient getHttpClient(){

        if(httpClient == null){

            //注册访问协议相关的Socket工厂         
            Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.INSTANCE)
                    .register("https", SSLConnectionSocketFactory.getSystemSocketFactory())
                    .build();

            //HttpConnection 工厂:配置写请求/解析响应处理器
            HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connectionFactory
              = new ManagedHttpClientConnectionFactory(DefaultHttpRequestWriterFactory.INSTANCE, 
                    DefaultHttpResponseParserFactory.INSTANCE);

            //DNS 解析器
            DnsResolver dnsResolver = SystemDefaultDnsResolver.INSTANCE;

            //创建池化连接管理器
            manager = new PoolingHttpClientConnectionManager(socketFactoryRegistry,connectionFactory,dnsResolver);

            //默认为Socket配置
            SocketConfig defaultSocketConfig = SocketConfig.custom().setTcpNoDelay(true).build();
            manager.setDefaultSocketConfig(defaultSocketConfig);

            manager.setMaxTotal(300); //设置整个连接池的最大连接数
            //每个路由的默认最大连接，每个路由实际最大连接数由DefaultMaxPerRoute控制，而MaxTotal是整个池子的最大数
            //设置过小无法支持大并发(ConnectionPoolTimeoutException) Timeout waiting for connection from pool
            manager.setDefaultMaxPerRoute(200);//每个路由的最大连接数
            //在从连接池获取连接时，连接不活跃多长时间后需要进行一次验证，默认为2s
            manager.setValidateAfterInactivity(5*1000);

            //默认请求配置
            RequestConfig defaultRequestConfig = RequestConfig.custom()
                    .setConnectTimeout(2*1000) //设置连接超时时间，2s
                    .setSocketTimeout(5*1000) //设置等待数据超时时间，5s
                    .setConnectionRequestTimeout(2*1000) //设置从连接池获取连接的等待超时时间
                    .build();

            HttpRequestRetryHandler retryHandler = new HttpRequestRetryHandler() {
                public boolean retryRequest(
                        IOException exception,
                        int executionCount,
                        HttpContext context) {
                    if (executionCount >= 5) {
                        // 超时次数
                        return false;
                    }
                    if (exception instanceof InterruptedIOException) {
                        // Timeout
                        return false;
                    }
                    if (exception instanceof UnknownHostException) {
                        // Unknown host
                        return false;
                    }
                    if (exception instanceof ConnectTimeoutException) {
                        // Connection refused
                        return false;
                    }
                    if (exception instanceof SSLException) {
                        // SSL handshake exception
                        return false;
                    }
                    HttpClientContext clientContext = HttpClientContext.adapt(context);
                    HttpRequest request = clientContext.getRequest();
                    boolean idempotent = !(request instanceof HttpEntityEnclosingRequest);
                    if (idempotent) {
                        // Retry if the request is considered idempotent
                        return true;
                    }
                    return false;
                }
            };


            //创建HttpClient
            httpClient = HttpClients.custom()
                    .setConnectionManager(manager)
                    .setConnectionManagerShared(false) //连接池不是共享模式
                    .evictIdleConnections(60, TimeUnit.SECONDS) //定期回收空闲连接
                    .evictExpiredConnections()// 定期回收过期连接
                    .setConnectionTimeToLive(60, TimeUnit.SECONDS) //连接存活时间，如果不设置，则根据长连接信息决定
                    .setDefaultRequestConfig(defaultRequestConfig) //设置默认请求配置
                    .setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE) //连接重用策略，即是否能keepAlive
                    .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE) //长连接配置，即获取长连接生产多长时间
                    .setRetryHandler(retryHandler) //设置重试次数，默认是3次
                    .build();


            //JVM 停止或重启时，关闭连接池释放掉连接(跟数据库连接池类似)

            Runtime.getRuntime().addShutdownHook(new Thread(){

                @Override
                public void run() {

                    try{
                        if(httpClient !=null){
                            httpClient.close();
                        }
                    }catch(IOException e){
                        e.printStackTrace();
                    }

                }

            });

        }

        return httpClient;
    }

}
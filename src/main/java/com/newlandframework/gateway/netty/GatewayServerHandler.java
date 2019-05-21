package com.newlandframework.gateway.netty;

import com.newlandframework.gateway.commons.GatewayAttribute;
import com.newlandframework.gateway.commons.HttpClientUtils;
import com.newlandframework.gateway.commons.RouteAttribute;
import com.newlandframework.gateway.commons.RoutingLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.Signal;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.util.StringUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.newlandframework.gateway.commons.GatewayOptions.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class GatewayServerHandler extends SimpleChannelInboundHandler<Object> {
    private HttpRequest request;
    private StringBuilder buffer = new StringBuilder();
    private String url = "";
    private String uri = "";
    private StringBuilder respone;
    private GlobalEventExecutor executor = GlobalEventExecutor.INSTANCE;
    private CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        System.out.println("channelReadComplete");
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = this.request = (HttpRequest) msg;
            if (HttpUtil.is100ContinueExpected(request)) {
                notify100Continue(ctx);
            }
            buffer.setLength(0);
            uri = request.uri().substring(1);
        }

        if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;
            ByteBuf content = httpContent.content();
            if (content.isReadable()) {
                buffer.append(content.toString(GATEWAY_OPTION_CHARSET));
            }
            if (msg instanceof LastHttpContent) {
                LastHttpContent trace = (LastHttpContent) msg;
                System.out.println("[NETTY-GATEWAY] REQUEST : " + buffer.toString());
                url = matchUrl();
                System.out.println("[NETTY-GATEWAY] URL : " + url);

                Future<StringBuilder> future = executor.submit(new Callable<StringBuilder>() {
                    @Override
                    public StringBuilder call() {
                        return HttpClientUtils.post(url, buffer.toString(), GATEWAY_OPTION_HTTP_POST);
                    }
                });

                future.addListener(new FutureListener<StringBuilder>() {
                    @Override
                    public void operationComplete(Future<StringBuilder> future) throws Exception {
                        if (future.isSuccess()) {
                            respone = future.get(GATEWAY_OPTION_HTTP_POST, TimeUnit.MILLISECONDS);
                        } else {
                            respone = new StringBuilder(((Signal) future.cause()).name());
                        }
                        latch.countDown();
                    }
                });

                try {
                    latch.await();
                    writeResponse(respone, future.isSuccess() ? trace : null, ctx);
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private String matchUrl() {
        for (GatewayAttribute gateway : RoutingLoader.GATEWAYS) {
            if (gateway.getServerPath().equals(uri)) {
                for (RouteAttribute route : RoutingLoader.ROUTERS) {
                    if (route.getServerPath().equals(uri)) {
                        String[] keys = StringUtils.delimitedListToStringArray(route.getKeyWord(), GATEWAY_OPTION_KEY_WORD_SPLIT);
                        boolean match = true;
                        for (String key : keys) {
                            if (key.isEmpty()) {
                                continue;
                            }
                            if (buffer.toString().indexOf(key.trim()) == -1) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            return route.getMatchAddr();
                        }
                    }
                }

                return gateway.getDefaultAddr();
            }
        }
        return GATEWAY_OPTION_LOCALHOST;
    }

    private void writeResponse(StringBuilder respone, HttpObject current, ChannelHandlerContext ctx) {
        if (respone != null) {
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, current == null ? OK : current.decoderResult().isSuccess() ? OK : BAD_REQUEST,
                    Unpooled.copiedBuffer(respone.toString(), GATEWAY_OPTION_CHARSET));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=GBK");
            if (keepAlive) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ctx.write(response);
        }
    }

    private static void notify100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, CONTINUE);
        ctx.write(response);
    }
}


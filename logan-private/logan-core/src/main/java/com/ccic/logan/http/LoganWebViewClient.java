package com.ccic.logan.http;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 包住宿主自己的 WebViewClient：只在页面开始/完成时多干一件事（注入 logan-h5.js），
 * 其余回调全部原样转发给宿主 client；宿主没有 client 时退化为系统默认行为。
 *
 * 只覆盖 WebViewClient 真实存在的方法（对齐 compileSdk 35 的公开签名）。
 * 早期版本误把 WebChromeClient 的 onJsAlert/onJsConfirm/onJsPrompt/onShowFileChooser 等
 * 当成 WebViewClient 方法来转发，编不过 —— 宿主自己的 WebChromeClient 我们完全不碰。
 * 用委托而不是继承，避免和宿主的 client 子类（常见 TBS/X5 封装）抢构造签名。
 */
public class LoganWebViewClient extends WebViewClient {

    private final WebViewClient delegate;

    public LoganWebViewClient(WebViewClient delegate) {
        this.delegate = delegate;
    }

    /** 取出宿主原始 client（供排查/单测）。 */
    public WebViewClient getDelegate() {
        return delegate;
    }

    private void injectQuiet(WebView view) {
        try {
            LoganH5Bridge.inject(view);
        } catch (Throwable ignore) {
            // 观测绝不影响业务
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        injectQuiet(view);
        if (delegate != null) {
            delegate.onPageStarted(view, url, favicon);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        // SPA 首屏之后才出现的路由页拿不到 onPageStarted，这里再兜一次（脚本内部有幂等闸门）
        injectQuiet(view);
        if (delegate != null) {
            delegate.onPageFinished(view, url);
        }
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        injectQuiet(view);
        if (delegate != null) {
            delegate.onPageCommitVisible(view, url);
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return delegate != null && delegate.shouldOverrideUrlLoading(view, url);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return delegate != null && delegate.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public void onLoadResource(WebView view, String url) {
        if (delegate != null) {
            delegate.onLoadResource(view, url);
        }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        if (delegate != null) {
            return delegate.shouldInterceptRequest(view, url);
        }
        return super.shouldInterceptRequest(view, url);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (delegate != null) {
            return delegate.shouldInterceptRequest(view, request);
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        if (delegate != null) {
            delegate.doUpdateVisitedHistory(view, url, isReload);
        }
    }

    @Override
    public void onTooManyRedirects(WebView view, Message dontResend, Message resend) {
        if (delegate != null) {
            delegate.onTooManyRedirects(view, dontResend, resend);
        } else {
            super.onTooManyRedirects(view, dontResend, resend);
        }
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        try {
            LoganH5Bridge.reportResourceError(failingUrl, errorCode, description);
        } catch (Throwable ignore) {
        }
        if (delegate != null) {
            delegate.onReceivedError(view, errorCode, description, failingUrl);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        try {
            String url = request == null ? null : String.valueOf(request.getUrl());
            LoganH5Bridge.reportResourceError(url, error == null ? -1 : error.getErrorCode(),
                    error == null ? null : String.valueOf(error.getDescription()));
        } catch (Throwable ignore) {
        }
        if (delegate != null) {
            delegate.onReceivedError(view, request, error);
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        try {
            if (request != null && response != null && response.getStatusCode() >= 400) {
                LoganHttp.recordNativeWebError(String.valueOf(request.getUrl()), response.getStatusCode(),
                        request.getMethod(), String.valueOf(response.getReasonPhrase()));
            }
        } catch (Throwable ignore) {
        }
        if (delegate != null) {
            delegate.onReceivedHttpError(view, request, response);
        }
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        if (delegate != null) {
            delegate.onReceivedSslError(view, handler, error);
        } else {
            handler.cancel();
        }
    }

    @Override
    public void onReceivedClientCertRequest(WebView view, android.webkit.ClientCertRequest request) {
        if (delegate != null) {
            delegate.onReceivedClientCertRequest(view, request);
        }
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
        if (delegate != null) {
            delegate.onReceivedHttpAuthRequest(view, handler, host, realm);
        }
    }

    @Override
    public void onFormResubmission(WebView view, Message dontResend, Message resend) {
        if (delegate != null) {
            delegate.onFormResubmission(view, dontResend, resend);
        } else {
            super.onFormResubmission(view, dontResend, resend);
        }
    }

    @Override
    public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
        return delegate != null && delegate.shouldOverrideKeyEvent(view, event);
    }

    @Override
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        if (delegate != null) {
            delegate.onUnhandledKeyEvent(view, event);
        }
    }

    @Override
    public void onScaleChanged(WebView view, float oldScale, float newScale) {
        if (delegate != null) {
            delegate.onScaleChanged(view, oldScale, newScale);
        }
    }

    @Override
    public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
        if (delegate != null) {
            delegate.onReceivedLoginRequest(view, realm, account, args);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        // 渲染进程被杀要能看见：记一条 native 失败事件（页面级，非单资源）
        try {
            LoganHttp.recordNativeWebError(view == null ? null : view.getUrl(), 0, "GET",
                    "render process gone, didCrash=" + (detail != null && detail.didCrash())
                            + ", rendererPriorityAtExit=" + (detail == null ? -1 : detail.rendererPriorityAtExit()));
        } catch (Throwable ignore) {
        }
        if (delegate != null) {
            return delegate.onRenderProcessGone(view, detail);
        }
        return super.onRenderProcessGone(view, detail);
    }
}
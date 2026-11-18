//
//  LoganHttpProtocol.h
//  Logan PoC —— 业务 HTTP 观测点（NSURLProtocol 代理模式，业界与 Firebase Performance 同款路线）。
//
//  通用性：不依赖任何三方网络库 —— AFNetworking 3.x 用的是
//  URLSessionConfiguration.defaultSessionConfiguration，注册后自动被覆盖观测；
//  业务自研 URLSession 只要走系统栈同样被观测；裸 NSURLSession 显式传
//  ephemeralConfiguration 的场景由 LoganHttp report: 显式入口兜底。
//
//  稳定性红线：
//   1) canInitWithRequest 无副作用、不抛异常，判定不了就返回 NO 交给系统正常走；
//   2) 自家上报请求与带自定义 protocolClasses 的请求一律放行不接管；
//   3) 转发失败必须回落到原始语义：任何异常都调用 client URLProtocol:didFailWithError:
//      让业务照常收到错误，而不是永远挂起。
//
#import <Foundation/Foundation.h>

// 自家上报/转发会话调用它：这些配置创建的会话不再被观测，杜绝采集回环。
FOUNDATION_EXPORT void LoganMarkSessionNoObserve(NSURLSessionConfiguration * _Nullable cfg);

NS_ASSUME_NONNULL_BEGIN

@interface LoganHttpProtocol : NSURLProtocol
/// 幂等安装（只注册一次）。由 LoganHttp start 调用。
+ (void)installObserver;
@end

NS_ASSUME_NONNULL_END

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/open_api/data")
@Slf4j
public class OpenApiController extends BaseController {

    ReentrantLock rlock = new ReentrantLock();

    /***
     * 全局 本地缓存 键为section_id value 是否报警
     * 主要为定时扫描mysql表，查询超时任务
     */
    private final static Cache<Long, Boolean> GLOBAL_ALERT_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(8)
            .initialCapacity(100)
            .maximumSize(1000)
            .expireAfterWrite(86400, TimeUnit.SECONDS)
            .build();

    /**
     * 问题：
     * 多线程编程
     * <p>
     * 当多个高并发线程都需要等待一个非常重量级的任务（比如网络请求或者数据库查询）返回的结果
     * 注：
     * 具体场景，数据库查询得到结果可以本地内存缓存起来，但在缓存为空的情况下，并发第一次访问可能会引起多次数据库请求
     * 要求：1，避免每个线程并发性的去多次重复计算任务
     **/
    @ApiOperation(value = "缓存穿透测试", notes = "缓存穿透测试")
    @GetMapping(value = "/cache_through_test")
    public JsonResponse cacheThroughTest(@RequestParam Integer id) {
        /*
          1.cache miss后 需要去查数据库，避免重复查询mysql
          2.QPS需要满足为多少，RT需要为多少
          */
        log.info("receive, id={} ", id);
        Object writeMysql = GLOBAL_ALERT_CACHE.getIfPresent(Long.valueOf(id));
        if (writeMysql == null) {
            //cache miss
            //方案1
            //优点：synchronized 是 Java 语言层面提供的语法，所以不需要考虑异常
            // 但由于synchronized 在获取锁时必须一直等待没有额外的尝试机制

//            synchronized (this) {
//                log.info("thread={}, id={}", Thread.currentThread(), id);
//                //此处为io操作
//                Object writeMysqlV2 = GLOBAL_ALERT_CACHE.getIfPresent(Long.valueOf(id));
//                if (writeMysqlV2 != null) {
//                    log.info("spin lock work, return id={}", writeMysqlV2);
//                    return success();
//                }
//                log.info("processing, id={} ", id);
//                GLOBAL_ALERT_CACHE.put(Long.valueOf(id), true);
//                return success();
//            }

            //方案3
            try {
                if (rlock.tryLock(1, TimeUnit.MILLISECONDS)) {
                    //double check 是否其他线程已经更新
                    log.info("get lock, id={}", id);
                    Object writeMysqlV2 = GLOBAL_ALERT_CACHE.getIfPresent(Long.valueOf(id));
                    if (writeMysqlV2 != null) {
                        log.info("writeMysqlV2 done id={}", id);
                        return success();
                    }
                    //如果还是没有获取到 去查数据库 并更新
                    //此处为 写 缓存
                    //避免多个线程到达此处，进行多次写
                    GLOBAL_ALERT_CACHE.put(Long.valueOf(id), true);
                    log.info("rlock write done id={}", id);
                } else {
                    //阻塞自旋获取 可以优化为非阻塞
                    //Thread.sleep( 可以优化为delay操作，delay操作可以是计数
                    int count = 3;
                    for (int i = 0; i < count; i++) {
                        Object writeMysqlV3 = GLOBAL_ALERT_CACHE.getIfPresent(Long.valueOf(id));
                        if (writeMysqlV3 != null) {
                            log.info("writeMysqlV3 done id={}, i={}", id, i);
                            return success();
                        }
                        for (int p = 0; p < 10000; p++) {
                        }
                    }
                    //如果还是没有获取到 去查数据库 并更新
                    //此处为 写 缓存
                    //避免多个线程到达此处，进行多次写
                    GLOBAL_ALERT_CACHE.put(Long.valueOf(id), true);
                    log.info("else write done id={}", id);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            log.info("use cache, id={}", id);
        }
        return success();
    }


}

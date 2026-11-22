package top.witzzz.moonbazaar

import android.app.Application
import android.content.Context
import top.witzzz.moonbazaar.data.Session
import top.witzzz.moonbazaar.data.SiteRefresh

/** Application 入口：在进程启动时初始化 Session 持久化依赖与官网登录态保活。 */
class MoonBazaarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(applicationContext)
        // 官网凭证每小时轮换：启动即刷新一次，之后每 45 分钟后台刷新。
        SiteRefresh.init(applicationContext)
    }
}

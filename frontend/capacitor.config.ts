import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.tripleh.interview',
  appName: '面试助手',

  // 指向空目录：本工程不打包 web 资源（页面由 server.url 远程加载），
  // 避免把 3.1MB 的 dist 白白塞进 APK，安装包只由原生壳决定大小。
  webDir: 'capacitor-webdir',

  // 不把 dist 打进 APK，直接加载自建站点：
  // 前端发版只需跑 scripts/deploy-tc-cloud.sh，APK 本身永远不用重装。
  // 站点使用自签名证书（SAN = IP:159.75.135.213），信任链由
  // android/app/src/main/res/xml/network_security_config.xml 里内置的 CA 提供，
  // 因此 WebView 不会报证书错误，也不需要 cleartext / allowMixedContent。
  server: {
    url: 'https://159.75.135.213',
  },

  android: {
    // 生产包关闭 WebView 远程调试（默认即 false，显式声明防止误开）
    webContentsDebuggingEnabled: false,
  },
};

export default config;

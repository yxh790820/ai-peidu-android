/* 复制为 secrets.js 并填上你自己的 key；secrets.js 不会被提交 */
window.PEIDU_SECRETS = {
  "current": "deepseek",
  "prompt": "翻译",
  "font": 14,
  "deepseek": {
    "base": "https://api.deepseek.com/v1",
    "model": "deepseek-flash",
    "key": ""
  },
  "local": {
    "base": "http://192.168.1.100:18080",
    "model": "local",
    "key": ""
  }
};

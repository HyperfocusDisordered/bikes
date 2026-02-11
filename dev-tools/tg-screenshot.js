const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const pages = await browser.pages();
  const tg = pages.find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }
  await tg.screenshot({path: '/tmp/tg-now.png'});
  console.log('OK url=' + tg.url());
  process.exit(0);
})();

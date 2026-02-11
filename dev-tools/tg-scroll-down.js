// Прокрутить чат Telegram Web вниз
const puppeteer = require('puppeteer');
(async () => {
  const b = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const tg = (await b.pages()).find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }
  await tg.evaluate(() => {
    const chat = document.querySelector('.bubbles-inner') ||
                 document.querySelector('.messages-container');
    if (chat) chat.scrollTop = chat.scrollHeight;
    // Также кликнем кнопку "scroll to bottom" если есть
    const scrollBtn = document.querySelector('.bubbles-go-down');
    if (scrollBtn) scrollBtn.click();
  });
  console.log('scrolled');
  process.exit(0);
})().catch(e => { console.error(e.message); process.exit(1); });

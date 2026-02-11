// Кликнуть на чат Karma Rent в списке слева
const puppeteer = require('puppeteer');
(async () => {
  const b = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const tg = (await b.pages()).find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }

  const result = await tg.evaluate(() => {
    const items = document.querySelectorAll('.chatlist-chat');
    for (const item of items) {
      const title = item.querySelector('.peer-title');
      if (title && title.textContent.includes('Karma Rent')) {
        item.click();
        return 'clicked Karma Rent chat';
      }
    }
    // Try another selector
    const dialogs = document.querySelectorAll('[data-peer-id]');
    for (const d of dialogs) {
      if (d.textContent.includes('Karma Rent')) {
        d.click();
        return 'clicked via data-peer-id';
      }
    }
    return 'not found';
  });
  console.log(result);
  process.exit(0);
})().catch(e => { console.error(e.message); process.exit(1); });

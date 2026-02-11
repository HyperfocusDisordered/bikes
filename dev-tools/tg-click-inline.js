// Кликнуть inline кнопку по тексту в последнем сообщении бота
const puppeteer = require('puppeteer');
const btnText = process.argv[2] || 'Байки';

(async () => {
  const b = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const tg = (await b.pages()).find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }

  const result = await tg.evaluate((text) => {
    // Найти все inline кнопки
    const btns = document.querySelectorAll('.reply-markup button');
    for (const btn of btns) {
      if (btn.textContent.includes(text)) {
        btn.click();
        return 'clicked: ' + btn.textContent.trim();
      }
    }
    // Попробуем .keyboard-button
    const btns2 = document.querySelectorAll('.keyboard-button');
    for (const btn of btns2) {
      if (btn.textContent.includes(text)) {
        btn.click();
        return 'clicked2: ' + btn.textContent.trim();
      }
    }
    return 'button not found: ' + text + ' (found ' + document.querySelectorAll('.reply-markup button, .keyboard-button').length + ' buttons)';
  }, btnText);
  console.log(result);
  process.exit(0);
})().catch(e => { console.error(e.message); process.exit(1); });

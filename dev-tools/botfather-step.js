/**
 * Stepwise BotFather interaction - check what happened and continue
 */
const puppeteer = require('puppeteer');

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
  const step = process.argv[2] || 'check';
  const text = process.argv[3];

  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    console.log('No Telegram tab. Opening...');
    page = await browser.newPage();
    await page.goto('https://web.telegram.org/k/#@BotFather');
    await sleep(5000);
    console.log('Opened. Run again with "check".');
    return;
  }

  if (step === 'goto') {
    await page.goto('https://web.telegram.org/k/#@BotFather');
    await sleep(3000);
    console.log('Navigated to BotFather');
  }

  if (step === 'check' || step === 'goto') {
    // Read last messages
    const msgs = await page.evaluate(() => {
      const messages = document.querySelectorAll('.message');
      return Array.from(messages).slice(-8).map(m => m.innerText.trim());
    });
    console.log('=== Last messages ===');
    msgs.forEach((m, i) => console.log(`[${i}] ${m}`));

    // Check chat title
    const title = await page.evaluate(() => {
      const el = document.querySelector('.top .peer-title');
      return el ? el.textContent : 'unknown';
    });
    console.log('\nChat:', title);
    return;
  }

  if (step === 'send') {
    if (!text) { console.log('Usage: node botfather-step.js send "text"'); return; }
    const inputSelector = '.input-message-input[contenteditable="true"]';
    await page.waitForSelector(inputSelector, { timeout: 5000 });
    await page.click(inputSelector);
    await sleep(200);
    await page.type(inputSelector, text, { delay: 30 });
    await sleep(200);
    await page.keyboard.press('Enter');
    console.log('Sent:', text);
    await sleep(2000);

    // Read response
    const msgs = await page.evaluate(() => {
      const messages = document.querySelectorAll('.message');
      return Array.from(messages).slice(-5).map(m => m.innerText.trim());
    });
    console.log('=== Last messages ===');
    msgs.forEach((m, i) => console.log(`[${i}] ${m}`));
    return;
  }

  if (step === 'screenshot') {
    await page.screenshot({ path: '/tmp/tg-current.png' });
    console.log('Screenshot: /tmp/tg-current.png');
    return;
  }
}

main().catch(console.error);

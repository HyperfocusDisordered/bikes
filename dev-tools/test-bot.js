const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  // Find or open Telegram tab
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    page = await browser.newPage();
    await page.goto('https://web.telegram.org/k/#@KarmaRentVnBot', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });
    await new Promise(r => setTimeout(r, 5000));
  } else {
    // Navigate to our bot
    await page.goto('https://web.telegram.org/k/#@KarmaRentVnBot', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });
    await new Promise(r => setTimeout(r, 3000));
  }

  console.log('On bot page');

  // Click START button if exists
  try {
    const startBtn = await page.$('button.btn-primary');
    if (startBtn) {
      const text = await page.evaluate(el => el.textContent, startBtn);
      if (text.includes('START') || text.includes('Start') || text.includes('RESTART')) {
        await startBtn.click();
        console.log('Clicked START button');
        await new Promise(r => setTimeout(r, 2000));
      }
    }
  } catch(e) {}

  // Send /start command
  const inputSelector = '.input-message-input[contenteditable="true"]';
  try {
    await page.waitForSelector(inputSelector, { timeout: 10000 });
    await page.click(inputSelector);
    await new Promise(r => setTimeout(r, 300));
    await page.type(inputSelector, '/start KR_001', { delay: 30 });
    await page.keyboard.press('Enter');
    console.log('Sent /start KR_001');

    // Wait for response
    await new Promise(r => setTimeout(r, 5000));

    // Screenshot
    await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/bot-test.png' });
    console.log('Screenshot saved');

    // Get last messages
    const messages = await page.evaluate(() => {
      const msgs = document.querySelectorAll('.bubble .message, .bubble .text-content');
      return Array.from(msgs).slice(-5).map(m => m.textContent.trim());
    });
    console.log('Last messages:', messages);

  } catch(e) {
    console.error('Error:', e.message);
  }

  browser.disconnect();
}

main().catch(console.error);

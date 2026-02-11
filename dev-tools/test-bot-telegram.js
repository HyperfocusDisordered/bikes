// Тест бота @KarmaRentVnBot через Telegram Web
// КРИТИЧНО: перед вводом ПРОВЕРИТЬ что открыт правильный чат!

const puppeteer = require('puppeteer');

async function testBot() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  const pages = await browser.pages();
  const page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    // Открыть Telegram Web с ботом
    const newPage = await browser.newPage();
    await newPage.goto('https://web.telegram.org/k/#@KarmaRentVnBot');
    await newPage.waitForTimeout(5000);
    console.log('Opened Telegram Web, navigated to bot');

    // Проверяем что открыт правильный чат
    const chatTitle = await newPage.evaluate(() => {
      const titleEl = document.querySelector('.chat-info .peer-title') ||
                       document.querySelector('.top .peer-title') ||
                       document.querySelector('[class*="TopBarTitle"]');
      return titleEl ? titleEl.textContent : 'NOT FOUND';
    });
    console.log('Chat title:', chatTitle);

    if (!chatTitle.includes('Karma') && !chatTitle.includes('karma')) {
      console.log('ERROR: Wrong chat! Expected KarmaRentVnBot, got:', chatTitle);
      console.log('Aborting to prevent sending to wrong person');
      return;
    }

    console.log('✅ Correct chat confirmed:', chatTitle);

    // Кликнуть START если есть кнопка
    const startBtn = await newPage.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const start = btns.find(b => b.textContent.toLowerCase().includes('start'));
      if (start) {
        start.click();
        return true;
      }
      return false;
    });

    if (startBtn) {
      console.log('Clicked START button');
    } else {
      // Ввести /start в поле ввода
      const input = await newPage.$('.input-message-input[contenteditable="true"]');
      if (input) {
        await input.click();
        await newPage.type('.input-message-input[contenteditable="true"]', '/start', { delay: 50 });
        await newPage.keyboard.press('Enter');
        console.log('Sent /start command');
      } else {
        console.log('Cannot find input field');
      }
    }

    await newPage.waitForTimeout(3000);

    // Прочитать ответ бота
    const lastMessage = await newPage.evaluate(() => {
      const msgs = document.querySelectorAll('.message');
      if (msgs.length > 0) {
        return msgs[msgs.length - 1].textContent;
      }
      return 'No messages found';
    });
    console.log('Bot response:', lastMessage.substring(0, 200));

  } else {
    console.log('Telegram Web already open at:', page.url());

    // Навигация к боту
    await page.goto('https://web.telegram.org/k/#@KarmaRentVnBot');
    await page.waitForTimeout(5000);

    // Проверяем чат
    const chatTitle = await page.evaluate(() => {
      const titleEl = document.querySelector('.chat-info .peer-title') ||
                       document.querySelector('.top .peer-title');
      return titleEl ? titleEl.textContent : 'NOT FOUND';
    });
    console.log('Chat title:', chatTitle);

    if (!chatTitle.includes('Karma') && !chatTitle.includes('karma')) {
      console.log('ERROR: Wrong chat! Aborting.');
      return;
    }

    console.log('✅ Correct chat confirmed');

    // START button или ввод
    const startBtn = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const start = btns.find(b => b.textContent.toLowerCase().includes('start'));
      if (start) { start.click(); return true; }
      return false;
    });

    if (!startBtn) {
      const input = await page.$('.input-message-input[contenteditable="true"]');
      if (input) {
        await input.click();
        await page.type('.input-message-input[contenteditable="true"]', '/start', { delay: 50 });
        await page.keyboard.press('Enter');
        console.log('Sent /start');
      }
    } else {
      console.log('Clicked START');
    }

    await page.waitForTimeout(3000);
    const lastMsg = await page.evaluate(() => {
      const msgs = document.querySelectorAll('.message');
      return msgs.length > 0 ? msgs[msgs.length - 1].textContent : 'No messages';
    });
    console.log('Response:', lastMsg.substring(0, 200));
  }
}

testBot().catch(console.error);

/**
 * Create @hyperfocus_disordered_bot via BotFather using Puppeteer + CDP
 * Connects to Arc on port 9222
 */
const puppeteer = require('puppeteer');

const BOT_NAME = 'HyperFocus Bridge';
const BOT_USERNAME = 'hyperfocus_disordered_bot';

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function main() {
  console.log('Connecting to Arc...');
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    console.log('No Telegram tab found, opening one...');
    page = await browser.newPage();
    await page.goto('https://web.telegram.org/k/#@BotFather');
    await sleep(5000);
  } else {
    console.log('Found Telegram tab:', page.url());
    // Navigate to BotFather
    await page.goto('https://web.telegram.org/k/#@BotFather');
    await sleep(3000);
  }

  console.log('Current URL:', page.url());

  // Wait for chat to load and find message input
  const inputSelector = '.input-message-input[contenteditable="true"]';

  try {
    await page.waitForSelector(inputSelector, { timeout: 10000 });
    console.log('Message input found!');
  } catch (e) {
    console.log('Message input not found, taking screenshot...');
    await page.screenshot({ path: '/tmp/tg-botfather.png' });
    console.log('Screenshot saved to /tmp/tg-botfather.png');
    return;
  }

  // Verify we're in BotFather chat
  const chatTitle = await page.evaluate(() => {
    const el = document.querySelector('.top .peer-title');
    return el ? el.textContent : 'unknown';
  });
  console.log('Chat title:', chatTitle);

  if (!chatTitle.includes('BotFather')) {
    console.log('ERROR: Not in BotFather chat! Aborting.');
    return;
  }

  // Step 1: Send /newbot
  console.log('Sending /newbot...');
  await page.click(inputSelector);
  await page.type(inputSelector, '/newbot', { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(3000);

  // Step 2: Send bot name
  console.log('Sending bot name:', BOT_NAME);
  await page.click(inputSelector);
  await page.type(inputSelector, BOT_NAME, { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(3000);

  // Step 3: Send bot username
  console.log('Sending bot username:', BOT_USERNAME);
  await page.click(inputSelector);
  await page.type(inputSelector, BOT_USERNAME, { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(5000);

  // Step 4: Read the response to get the token
  console.log('Reading response...');
  const lastMessages = await page.evaluate(() => {
    const msgs = document.querySelectorAll('.message');
    const texts = [];
    const last5 = Array.from(msgs).slice(-5);
    for (const m of last5) {
      texts.push(m.textContent);
    }
    return texts;
  });

  console.log('\n--- Last messages from BotFather ---');
  for (const msg of lastMessages) {
    console.log(msg);
    console.log('---');
  }

  // Try to extract token
  const tokenMatch = lastMessages.join('\n').match(/\d+:[\w-]+/);
  if (tokenMatch) {
    console.log('\n=== BOT TOKEN ===');
    console.log(tokenMatch[0]);
    console.log('=================');
  } else {
    console.log('\nToken not found in messages. Check screenshot.');
    await page.screenshot({ path: '/tmp/tg-botfather-result.png' });
    console.log('Screenshot saved to /tmp/tg-botfather-result.png');
  }
}

main().catch(console.error);

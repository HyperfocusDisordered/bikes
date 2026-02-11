const puppeteer = require('puppeteer');

async function main() {
  // Connect to already running Arc browser via CDP
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  console.log('Connected to Arc browser');

  // Open new page with Telegram Web K BotFather
  const page = await browser.newPage();
  await page.goto('https://web.telegram.org/k/#@BotFather', {
    waitUntil: 'networkidle2',
    timeout: 30000
  });

  console.log('Navigated to Telegram Web K BotFather');

  // Wait for the page to fully load
  await page.waitForSelector('.chat', { timeout: 15000 }).catch(() => {});
  await new Promise(r => setTimeout(r, 3000));

  console.log('Page loaded, looking for message input...');

  // Find the message input (contenteditable div)
  const inputSelector = '.input-message-input[contenteditable="true"]';

  try {
    await page.waitForSelector(inputSelector, { timeout: 10000 });
    console.log('Found message input');

    // Click on it to focus
    await page.click(inputSelector);
    await new Promise(r => setTimeout(r, 500));

    // Type the bot name
    await page.type(inputSelector, 'Karma Rent', { delay: 50 });
    console.log('Typed "Karma Rent"');

    // Take screenshot to verify
    await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/telegram-before-send.png' });
    console.log('Screenshot saved');

    // Press Enter to send
    await page.keyboard.press('Enter');
    console.log('Pressed Enter - message sent!');

    // Wait for BotFather response
    await new Promise(r => setTimeout(r, 3000));

    // Take screenshot of response
    await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/telegram-after-send.png' });
    console.log('Response screenshot saved');

    // Now BotFather will ask for username. Type it.
    await page.click(inputSelector);
    await new Promise(r => setTimeout(r, 500));
    await page.type(inputSelector, 'KarmaRentVnBot', { delay: 50 });
    console.log('Typed "KarmaRentVnBot"');

    await page.keyboard.press('Enter');
    console.log('Pressed Enter - username sent!');

    // Wait for response with token
    await new Promise(r => setTimeout(r, 5000));

    // Take final screenshot
    await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/telegram-final.png' });
    console.log('Final screenshot saved');

    // Try to extract the bot token from the page
    const pageContent = await page.evaluate(() => {
      const messages = document.querySelectorAll('.message');
      const texts = [];
      messages.forEach(m => texts.push(m.textContent));
      return texts.join('\n---\n');
    });

    // Look for token pattern
    const tokenMatch = pageContent.match(/(\d+:[A-Za-z0-9_-]+)/);
    if (tokenMatch) {
      console.log('BOT TOKEN FOUND: ' + tokenMatch[1]);
    } else {
      console.log('Token not found in messages yet. Check screenshots.');
      console.log('Last messages:', pageContent.slice(-500));
    }

  } catch (err) {
    console.error('Error:', err.message);

    // Debug: list all contenteditable elements
    const editables = await page.evaluate(() => {
      const els = document.querySelectorAll('[contenteditable]');
      return Array.from(els).map(e => ({
        tag: e.tagName,
        class: e.className,
        contenteditable: e.contentEditable,
        text: e.textContent.substring(0, 50)
      }));
    });
    console.log('Contenteditable elements:', JSON.stringify(editables, null, 2));
  }

  // Don't disconnect - keep browser running
  browser.disconnect();
  console.log('Done!');
}

main().catch(console.error);

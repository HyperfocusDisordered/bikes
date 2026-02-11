const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  console.log('Connected to Arc');

  const oauthUrl = process.argv[2];
  if (!oauthUrl) {
    console.error('Usage: node cloudflare-oauth.js <oauth-url>');
    browser.disconnect();
    return;
  }

  const page = await browser.newPage();
  console.log('Navigating to OAuth URL...');
  await page.goto(oauthUrl, { waitUntil: 'networkidle2', timeout: 30000 });

  await new Promise(r => setTimeout(r, 2000));
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-oauth-1.png' });

  // Check if we need to click "Allow" or if it auto-redirected
  const url = page.url();
  console.log('Current URL:', url);

  if (url.includes('localhost:8976')) {
    console.log('Already redirected to callback! OAuth successful.');
    browser.disconnect();
    return;
  }

  // Look for Allow/Authorize button
  const buttons = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    return Array.from(btns).map(b => ({
      text: b.textContent.trim(),
      type: b.type,
      class: b.className
    }));
  });
  console.log('Buttons found:', JSON.stringify(buttons));

  // Try clicking Allow button
  for (const text of ['Allow', 'Authorize', 'Continue', 'Confirm']) {
    try {
      const btn = await page.$x(`//button[contains(text(), '${text}')]`);
      if (btn.length > 0) {
        console.log(`Clicking "${text}" button...`);
        await btn[0].click();
        await new Promise(r => setTimeout(r, 3000));
        console.log('After click URL:', page.url());
        break;
      }
    } catch(e) {}
  }

  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-oauth-2.png' });
  console.log('Final URL:', page.url());

  browser.disconnect();
  console.log('Done');
}

main().catch(console.error);

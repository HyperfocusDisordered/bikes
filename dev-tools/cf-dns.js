const puppeteer = require('puppeteer');

// Add DNS records through Cloudflare Dashboard UI using Puppeteer
async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  console.log('Connected to Arc');

  const page = await browser.newPage();

  // Navigate to Cloudflare DNS settings for the zone
  const zoneId = '44ff21629c9f7043dcca286521bd04d0';
  await page.goto(`https://dash.cloudflare.com/${zoneId}/dns/records`, {
    waitUntil: 'networkidle2',
    timeout: 30000
  });

  console.log('Navigated to DNS records page');
  await new Promise(r => setTimeout(r, 3000));
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-dns-1.png' });
  console.log('Screenshot 1 saved');

  // Check URL - might need account ID in the URL
  console.log('Current URL:', page.url());

  // Try alternative URL format
  const accountId = 'bdbd784a32d8b0d9a75bf243b09e9c7d';
  await page.goto(`https://dash.cloudflare.com/${accountId}/karmarent.app/dns/records`, {
    waitUntil: 'networkidle2',
    timeout: 30000
  });

  await new Promise(r => setTimeout(r, 3000));
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-dns-2.png' });
  console.log('Screenshot 2 saved. URL:', page.url());

  // Look for "Add record" button
  const buttons = await page.evaluate(() => {
    const btns = document.querySelectorAll('button, a');
    return Array.from(btns)
      .filter(b => b.textContent.includes('Add') || b.textContent.includes('record'))
      .map(b => ({ text: b.textContent.trim().substring(0, 50), tag: b.tagName }));
  });
  console.log('Add buttons:', JSON.stringify(buttons));

  browser.disconnect();
  console.log('Done');
}

main().catch(console.error);

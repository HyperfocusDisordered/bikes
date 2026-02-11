const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('cloudflare.com'));

  // Scroll down to see the form
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await new Promise(r => setTimeout(r, 500));

  const inputs = await page.$$('input');
  console.log(`Total inputs: ${inputs.length}`);

  for (let i = 0; i < inputs.length; i++) {
    const info = await page.evaluate(el => {
      const rect = el.getBoundingClientRect();
      return {
        type: el.type,
        id: el.id,
        name: el.name,
        placeholder: el.placeholder,
        value: el.value,
        width: rect.width,
        height: rect.height,
        y: Math.round(rect.y),
        disabled: el.disabled,
        readOnly: el.readOnly
      };
    }, inputs[i]);

    console.log(`[${i}] type=${info.type} id="${info.id}" name="${info.name}" ph="${info.placeholder}" w=${info.width} h=${info.height} y=${info.y}`);
  }

  // Also look for the form using text content
  const formLabels = await page.evaluate(() => {
    const labels = document.querySelectorAll('label');
    return Array.from(labels).map(l => ({
      text: l.textContent.trim().substring(0, 40),
      hasInput: !!l.querySelector('input'),
      for: l.getAttribute('for')
    })).filter(l => l.text.includes('Name') || l.text.includes('address') || l.text.includes('IPv') || l.text.includes('Target'));
  });
  console.log('\nRelevant labels:', JSON.stringify(formLabels, null, 2));

  browser.disconnect();
}

main().catch(console.error);

const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  const page = pages.find(p => p.url().includes('cloudflare.com'));

  // Find the Name input by exploring DOM near the label
  const nameInfo = await page.evaluate(() => {
    const label = document.querySelector('label[for="name"]');
    if (!label) return 'no label found';

    // The input might be a sibling of the label's parent
    const formField = label.closest('div');
    const result = [];

    // Walk up and look for inputs
    let container = formField;
    for (let i = 0; i < 5; i++) {
      if (!container) break;
      const inputs = container.querySelectorAll('input');
      inputs.forEach(inp => {
        result.push({
          level: i,
          tag: inp.tagName,
          id: inp.id,
          name: inp.name,
          type: inp.type,
          rect: {
            w: Math.round(inp.getBoundingClientRect().width),
            h: Math.round(inp.getBoundingClientRect().height),
            y: Math.round(inp.getBoundingClientRect().y)
          }
        });
      });
      container = container.parentElement;
    }

    // Also check if there's an input with name="name" or id="name"
    const nameInput = document.getElementById('name') || document.querySelector('[name="name"]');
    if (nameInput) {
      result.push({
        special: 'direct-id',
        tag: nameInput.tagName,
        id: nameInput.id,
        type: nameInput.type,
        rect: {
          w: Math.round(nameInput.getBoundingClientRect().width),
          h: Math.round(nameInput.getBoundingClientRect().height)
        }
      });
    }

    return result;
  });

  console.log('Name field analysis:', JSON.stringify(nameInfo, null, 2));

  // Try a different approach: find ALL visible text inputs
  const visibleInputs = await page.evaluate(() => {
    const inputs = document.querySelectorAll('input[type="text"]');
    return Array.from(inputs)
      .filter(i => i.getBoundingClientRect().height > 0)
      .map(i => {
        const rect = i.getBoundingClientRect();
        const nearText = [];
        let el = i.previousElementSibling || i.parentElement;
        for (let j = 0; j < 3; j++) {
          if (el) {
            nearText.push(el.textContent?.trim()?.substring(0, 30));
            el = el.previousElementSibling || el.parentElement;
          }
        }
        return {
          id: i.id,
          name: i.name,
          y: Math.round(rect.y),
          w: Math.round(rect.width),
          nearText
        };
      });
  });
  console.log('\nVisible text inputs:', JSON.stringify(visibleInputs, null, 2));

  browser.disconnect();
}

main().catch(console.error);

const puppeteer = require('puppeteer');
const path = require('path');

(async () => {
  const browser = await puppeteer.launch();
  const page = await browser.newPage();
  const filePath = path.resolve('opra-commercial-license-report.html');
  await page.goto(`file://${filePath}`, {waitUntil: 'networkidle0'});
  await page.pdf({
    path: 'opra-commercial-license-report.pdf',
    format: 'A4',
    printBackground: true,
    margin: {
      top: '20px',
      bottom: '20px',
      left: '20px',
      right: '20px'
    }
  });

  await browser.close();
})();

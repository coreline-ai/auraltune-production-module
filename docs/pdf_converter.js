const puppeteer = require('puppeteer');
const path = require('path');

(async () => {
  const inputFile = process.argv[2];
  const outputFile = process.argv[3];
  
  if (!inputFile || !outputFile) {
    console.error('Usage: node pdf_converter.js <inputFile> <outputFile>');
    process.exit(1);
  }

  const browser = await puppeteer.launch();
  const page = await browser.newPage();
  const filePath = path.resolve(inputFile);
  
  await page.goto(`file://${filePath}`, {waitUntil: 'networkidle0'});
  await page.pdf({
    path: outputFile,
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

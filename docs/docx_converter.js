const fs = require('fs');
const HTMLtoDOCX = require('html-to-docx');

(async () => {
  try {
    const htmlString = fs.readFileSync('opra-commercial-license-report.html', 'utf-8');
    const fileBuffer = await HTMLtoDOCX(htmlString, null, {
      table: { row: { cantSplit: true } },
      footer: true,
      pageNumber: true,
    });

    fs.writeFileSync('opra-commercial-license-report.docx', fileBuffer);
    console.log('Successfully created opra-commercial-license-report.docx');
  } catch (error) {
    console.error('Error generating DOCX:', error);
  }
})();

document.getElementById('single-link-form').addEventListener('submit', function (e) {
    e.preventDefault();
    const videoLink = document.getElementById('videoLink').value;
    // Call your backend to handle the single video download
    console.log('Single Video Link:', videoLink);
    alert('Video download initiated: ' + videoLink);
});

document.getElementById('bulk-link-form').addEventListener('submit', function (e) {
    e.preventDefault();
    const excelFile = document.getElementById('excelFile').files[0];
    // Call your backend to handle bulk video downloads from the Excel sheet
    console.log('Bulk Video File:', excelFile);
    alert('Bulk video download initiated for Excel file: ' + excelFile.name);
});
const fs = require('fs');
const path = require('path');

function walk(dir) {
  let results = [];
  const list = fs.readdirSync(dir);
  list.forEach(file => {
    const filePath = path.join(dir, file);
    const stat = fs.statSync(filePath);
    if (stat && stat.isDirectory()) {
      results = results.concat(walk(filePath));
    } else if (filePath.endsWith('.vue') || filePath.endsWith('.js')) {
      results.push(filePath);
    }
  });
  return results;
}

const files = walk('/app/oujili/uni-client');
let changedFiles = 0;

files.forEach(file => {
  let content = fs.readFileSync(file, 'utf-8');
  let originalContent = content;
  
  // Replace:
  // this.tipMsg = res.data.msg;
  // this.$refs.elm.showDialog();
  // We can use a regex that matches this.tipMsg = res.data.msg; followed by any whitespace and this.$refs.elm.showDialog();
  const regex = /this\.tipMsg\s*=\s*res\.data\.msg;?\s*this\.\$refs\.elm\.showDialog\(\);?/g;
  
  content = content.replace(regex, '');
  
  if (content !== originalContent) {
    fs.writeFileSync(file, content, 'utf-8');
    changedFiles++;
    console.log('Changed', file);
  }
});

console.log('Total files changed:', changedFiles);

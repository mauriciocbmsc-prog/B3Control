// Integração em navegador com contas e banco temporários.
const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const crypto=require('node:crypto');const net=require('node:net');
(async()=>{
 const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'b3-browser-'));let server,browser;
 const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p));});});
 const base=`http://127.0.0.1:${port}`,password=crypto.randomBytes(24).toString('hex');let output='';
 try{
  server=spawn('./run.sh',[],{cwd:path.resolve(__dirname,'..'),env:{...process.env,PORT:String(port),B3_DB_PATH:path.join(tmp,'db'),B3_ADMIN_PASSWORD:password},stdio:['ignore','pipe','pipe']});
  server.stdout.on('data',d=>output+=d);server.stderr.on('data',d=>output+=d);
  let ready=false;for(let i=0;i<200;i++){if(server.exitCode!==null)throw Error(output);try{const r=await fetch(base+'/api/me');if(r.ok){ready=true;break}}catch{}await new Promise(r=>setTimeout(r,100));}if(!ready)throw Error(output||'Servidor indisponível');
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',args:['--no-sandbox']});
  const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.locator('#real-mode').click();await page.locator('#login-button').click();
  async function login(name){await page.locator('#login-form [name=login]').fill(name);await page.locator('#login-form [name=password]').fill(password);await page.locator('#login-form [type=submit]').click();await page.locator('#login-dialog').waitFor({state:'hidden'});}
  await login('b3');await page.locator('#users-button').click();
  await page.locator('#user-form [name=name]').fill('Comandante Teste');await page.locator('#user-form [name=login]').fill('cmd');await page.locator('#user-form [name=role]').selectOption('COMANDANTE');await page.locator('#user-form [name=unit]').fill('1ª CBM');await page.locator('#user-form [name=password]').fill(password);await page.locator('#user-form [type=submit]').click();await page.getByText('Usuário cadastrado.',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Fechar usuários',exact:true}).click();
  async function api(endpoint,data){return page.evaluate(async({endpoint,data})=>{const me=await(await fetch('/api/me')).json();const r=await fetch(endpoint,{method:'POST',headers:{'X-CSRF-Token':me.csrf},body:new URLSearchParams(data)});if(!r.ok)throw Error(await r.text());return r.json();},{endpoint,data});}
  for(const [login,name]of[['bm1','Bombeiro Um'],['bm2','Bombeiro Dois']])await api('/api/users',{login,name,role:'BOMBEIRO',unit:'1ª CBM',password});
  await page.locator('#new').click();const form=page.locator('#form');for(const [name,value]of Object.entries({title:'Treinamento de teste',code:'TESTE',city:'Canoinhas',deadline:'2099-01-01',start_date:'2099-02-01',end_date:'2099-02-02',hours:'16',vacancies:'2',requirements:'Exemplo'}))await form.locator(`[name=${name}]`).fill(value);
  await form.locator('[type=submit]').click();await page.locator('#create').waitFor({state:'hidden'});await page.locator('.card').first().waitFor();
  await page.locator('#logout-button').click();await page.locator('#login-button').click();await login('bm1');if(await page.locator('#new').isVisible())throw Error('Cadastro visível ao bombeiro');await page.locator('.card').first().click();await page.getByRole('button',{name:'Quero me inscrever',exact:true}).click();await page.getByText(/Inscrição voluntária · PENDENTE/).waitFor();await page.getByRole('button',{name:'Fechar detalhes',exact:true}).click();
  await page.locator('#logout-button').click();await page.locator('#login-button').click();await login('cmd');await page.locator('.card').first().click();await page.locator('[data-review]').selectOption('APROVADO');await page.waitForResponse(r=>r.url().endsWith('/applications')&&r.status()===200);await page.locator('#nominee').selectOption({label:'Bombeiro Dois'});await page.getByRole('button',{name:'Inscrever por indicação',exact:true}).click();await page.locator('.applicant').nth(1).waitFor();
  let priority=page.waitForResponse(r=>r.url().endsWith('/priority')&&r.status()===200);await page.locator('.applicant').nth(1).dragTo(page.locator('.applicant').first());await priority;await page.waitForResponse(r=>r.url().endsWith('/applications')&&r.status()===200);await page.waitForFunction(()=>document.querySelector('.applicant b')?.textContent.includes('Bombeiro Dois'));
  priority=page.waitForResponse(r=>r.url().endsWith('/priority')&&r.status()===200);await page.getByRole('button',{name:'Subir Bombeiro Um',exact:true}).click();await priority;await page.waitForFunction(()=>document.querySelector('.applicant b')?.textContent.includes('Bombeiro Um'));
  await page.getByRole('button',{name:'Fechar detalhes',exact:true}).click();await page.locator('#search').fill('inexistente');await page.locator('.empty').waitFor();await page.locator('#search').fill('');await page.setViewportSize({width:390,height:844});if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Overflow horizontal no celular');await page.locator('.card').first().click();await page.locator('#details[open]').waitFor();if(await page.locator('#details').evaluate(d=>d.scrollWidth>d.clientWidth))throw Error('Overflow no painel móvel');if(errors.length)throw Error(errors.join(';'));
  console.log('PASS: login, cadastro de usuário/edital, inscrição, aprovação, indicação, arraste, setas, pesquisa e layout móvel sem erros JS.');
 }finally{if(browser)await browser.close();if(server){server.kill('SIGTERM');await new Promise(r=>server.once('exit',r));}fs.rmSync(tmp,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});

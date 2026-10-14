// Opt-in local administration only. Bind a real socket to loopback and attest
// requests with a fresh private capability. Never expose the backend bearer.
import http from 'node:http';
import {randomBytes} from 'node:crypto';
import next from 'next';
const port=Number(process.env.PORT ?? 3000);
if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid local preview port');
if(!process.env.REGRESSLAB_INGESTION_TOKEN || process.env.REGRESSLAB_INGESTION_TOKEN.length<32 || process.env.REGRESSLAB_INGESTION_TOKEN.length>256)throw Error('Privately configure the local administrator token');
const api=new URL(process.env.REGRESSLAB_API_URL ?? 'http://127.0.0.1:8080');
if(api.protocol!=='http:'||!['127.0.0.1','[::1]'].includes(api.hostname)||api.username||api.password||api.search||api.hash||api.pathname!=='/')throw Error('Backend must use a loopback HTTP origin');
const capability=randomBytes(32).toString('hex');
process.env.REGRESSLAB_LOCAL_AI_CAPABILITY=capability;
process.env.REGRESSLAB_LOCAL_AI_ORIGIN=`http://127.0.0.1:${port}`;
process.env.REGRESSLAB_AI_UI_ENABLED='true';
const app=next({dev:false,hostname:'127.0.0.1',port});
await app.prepare();
const handler=app.getRequestHandler();
http.createServer((req,res)=>{
  // Remove attacker-supplied attestations. Proxied requests cannot gain write access.
  delete req.headers['x-regresslab-local-ai'];
  const peer=req.socket.remoteAddress;
  if(['127.0.0.1','::1','::ffff:127.0.0.1'].includes(peer) && !req.headers.forwarded && !req.headers['x-forwarded-for'] && !req.headers['x-real-ip'])
    req.headers['x-regresslab-local-ai']=capability;
  handler(req,res);
}).listen(port,'127.0.0.1',()=>console.log(`Protected local AI preview: http://127.0.0.1:${port}`));

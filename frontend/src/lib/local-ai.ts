// Server-only boundary: imported only by the API route and server components.
import { timingSafeEqual } from "node:crypto";

export async function boundedText(body:ReadableStream<Uint8Array>|null,limit:number) {
  if(!body) throw new Error("Empty body");
  const reader=body.getReader(), chunks:Uint8Array[]=[]; let total=0;
  try { while(true){ const {value,done}=await reader.read();if(done)break;total+=value.byteLength;
    if(total>limit)throw new Error("Oversized body");chunks.push(value); }
    const bytes=new Uint8Array(total);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
    return new TextDecoder("utf-8",{fatal:true}).decode(bytes);
  } finally { await reader.cancel().catch(()=>{});reader.releaseLock(); }
}

export function localAiConfiguration() {
  const token=process.env.REGRESSLAB_INGESTION_TOKEN, key=process.env.REGRESSLAB_LOCAL_AI_CAPABILITY;
  let local=false;
  try { const u=new URL(process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080");
    local=u.protocol==="http:" && ["127.0.0.1","[::1]"].includes(u.hostname) && !u.username && !u.password && !u.search && !u.hash && u.pathname==="/";
  } catch { /* reject invalid configuration */ }
  let boundOrigin=false;
  try { const u=new URL(process.env.REGRESSLAB_LOCAL_AI_ORIGIN??"");boundOrigin=u.protocol==="http:" && u.hostname==="127.0.0.1" && Number(u.port)>=1024 && !u.username && !u.password && u.origin===process.env.REGRESSLAB_LOCAL_AI_ORIGIN; }catch{}
  return {enabled:process.env.REGRESSLAB_AI_UI_ENABLED==="true" && !!key && /^[0-9a-f]{64}$/.test(key) && !!token && token.length>=32 && token.length<=256 && local && boundOrigin};
}
export function authorizedLocalUiRequest(request:Request) {
  if(!localAiConfiguration().enabled) return false;
  const expected=process.env.REGRESSLAB_LOCAL_AI_CAPABILITY!, actual=request.headers.get("x-regresslab-local-ai") ?? "";
  if(!/^[0-9a-f]{64}$/.test(actual) || !timingSafeEqual(Buffer.from(actual),Buffer.from(expected))) return false;
  const origin=request.headers.get("origin"), host=request.headers.get("host");
  if(!origin || !host || request.headers.get("sec-fetch-site")!=="same-origin") return false;
  try { const u=new URL(origin);
    // Next normalizes custom-server request URLs to an internal origin. Match
    // the trusted launcher's actual bound origin, not that framework URL.
    return origin===process.env.REGRESSLAB_LOCAL_AI_ORIGIN && u.host===host;
  } catch { return false; }
}

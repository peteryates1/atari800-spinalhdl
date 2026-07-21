#ifdef HAVE_WIFI
// Tiny HTTP/1.1 server on raw lwIP TCP for the SD manager. Routes:
//   GET  /                       -> the browser UI (one self-contained HTML page)
//   GET  /api/list?dir=/p        -> JSON directory listing
//   POST /api/upload?path=/p/f   -> write the raw request body to that SD path
//   POST /api/delete?path=/p/f   -> delete that SD file
// Uploads stream straight to FatFs (no full-file buffering); responses stream out
// via the tcp_sent callback so pages/listings of any size work. One upload at a
// time (the SD/FatFs is a single resource). Functions that may finish + close the
// connection return true ("closed") so the recv loop stops touching freed state.
#include "httpsrv.h"
#include "boot.h"                 // cdc_printf
#include "lwip/tcp.h"
#include "lib/fatfs/source/ff.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static struct tcp_pcb *s_listen;

// Single active upload.
static FIL  g_upfile;
static bool g_uploading;

typedef struct {
    struct tcp_pcb *pcb;
    char     hdr[800];
    int      hdrlen;
    bool     hdr_done;
    bool     is_post;
    char     path[320];          // request path + query (raw)
    uint32_t content_len;
    uint32_t body_recv;
    bool     mine_upload;        // this conn owns g_upfile
    char    *resp;               // heap response buffer, streamed out
    uint32_t resp_off, resp_len;
} conn_t;

// ---- helpers ----------------------------------------------------------------

static void url_decode(char *s) {
    char *o = s;
    for (char *p = s; *p; p++) {
        if (*p == '%' && p[1] && p[2]) {
            int hi = p[1], lo = p[2];
            hi = (hi <= '9') ? hi - '0' : (hi | 0x20) - 'a' + 10;
            lo = (lo <= '9') ? lo - '0' : (lo | 0x20) - 'a' + 10;
            *o++ = (char)((hi << 4) | lo); p += 2;
        } else *o++ = *p;
    }
    *o = 0;
}

static bool query_param(const conn_t *c, const char *key, char *out, int outlen) {
    const char *q = strchr(c->path, '?');
    if (!q) return false;
    char pat[16]; int n = snprintf(pat, sizeof pat, "%s=", key);
    const char *p = q + 1;
    while (p && *p) {
        if (strncmp(p, pat, n) == 0) {
            p += n; int i = 0;
            while (*p && *p != '&' && i < outlen - 1) out[i++] = *p++;
            out[i] = 0; url_decode(out); return true;
        }
        p = strchr(p, '&'); if (p) p++;
    }
    return false;
}

static void close_conn(conn_t *c) {
    struct tcp_pcb *pcb = c->pcb;
    if (c->mine_upload && g_uploading) { f_close(&g_upfile); g_uploading = false; }
    if (c->resp) free(c->resp);
    tcp_arg(pcb, NULL); tcp_recv(pcb, NULL); tcp_sent(pcb, NULL); tcp_err(pcb, NULL);
    free(c);
    if (tcp_close(pcb) != ERR_OK) tcp_abort(pcb);
}

// Stream the pending response; returns true when the connection is finished (closed).
static bool push_send(conn_t *c) {
    while (c->resp_off < c->resp_len) {
        uint16_t sb = tcp_sndbuf(c->pcb);
        if (sb == 0) { tcp_output(c->pcb); return false; }   // wait for tcp_sent
        uint32_t rem = c->resp_len - c->resp_off;
        uint16_t nn = rem < sb ? (uint16_t)rem : sb;
        err_t e = tcp_write(c->pcb, c->resp + c->resp_off, nn, TCP_WRITE_FLAG_COPY);
        if (e == ERR_MEM) { tcp_output(c->pcb); return false; }
        if (e != ERR_OK) { close_conn(c); return true; }
        c->resp_off += nn;
    }
    tcp_output(c->pcb);
    close_conn(c);
    return true;
}

static bool respond(conn_t *c, int code, const char *ctype, const char *body, uint32_t blen) {
    const char *st = code == 200 ? "200 OK" : code == 404 ? "404 Not Found"
                   : code == 400 ? "400 Bad Request" : "500 Internal Error";
    char h[160];
    int hl = snprintf(h, sizeof h,
        "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %lu\r\n"
        "Connection: close\r\nCache-Control: no-store\r\n\r\n",
        st, ctype, (unsigned long)blen);
    char *buf = malloc(hl + blen);
    if (!buf) { close_conn(c); return true; }
    memcpy(buf, h, hl);
    if (body && blen) memcpy(buf + hl, body, blen);
    c->resp = buf; c->resp_off = 0; c->resp_len = hl + blen;
    return push_send(c);
}
static bool respond_text(conn_t *c, int code, const char *msg) {
    return respond(c, code, "text/plain", msg, strlen(msg));
}

// ---- the browser UI (one page) ----------------------------------------------

static const char PAGE[] =
"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
"<title>Atari SD</title><style>"
"body{font:15px system-ui,sans-serif;margin:0;background:#111;color:#eee}"
"header{background:#7b1e1e;padding:10px 14px;font-weight:600}"
"main{max-width:760px;margin:0 auto;padding:14px}"
"#path{color:#fb4;margin:8px 0;font-family:monospace;word-break:break-all}"
".crumb{cursor:pointer;color:#7fd0ff}.crumb:hover{text-decoration:underline}"
"ul{list-style:none;padding:0;margin:0}"
"li{display:flex;align-items:center;gap:8px;padding:8px 6px;border-bottom:1px solid #333}"
"li:hover{background:#1c1c1c}.n{flex:1;cursor:pointer;word-break:break-all}"
".d{color:#7fd0ff}.sz{color:#888;font-size:12px}"
"button{background:#333;color:#eee;border:1px solid #555;border-radius:5px;padding:4px 9px;cursor:pointer}"
"button:hover{background:#444}.up{background:#2b5c2b}"
"#drop{border:2px dashed #555;border-radius:8px;padding:16px;text-align:center;color:#aaa;margin-top:12px}"
"#drop.hi{border-color:#2b5c2b;color:#eee}#log{color:#8f8;font-size:13px;margin-top:8px;white-space:pre-wrap}"
"</style>"
"<header>Atari 800 &mdash; SD card manager</header><main>"
"<div id=path></div>"
"<div style='margin:6px 0;display:flex;gap:8px;align-items:center'>"
"<button id=mk>+ New folder</button>"
"<input id=q placeholder='filter this folder\\u2026' style='flex:1;background:#1c1c1c;color:#eee;border:1px solid #555;border-radius:5px;padding:5px 8px'>"
"</div>"
"<ul id=list></ul>"
"<div id=drop>Drop files here, or <button class=up id=pick>choose files</button> to upload to this folder"
"<input id=file type=file multiple hidden></div><div id=log></div>"
"<script>"
"let dir='/';"
"const $=s=>document.querySelector(s);"
"function j(p,o){return fetch(p,o).then(r=>r.ok?r:Promise.reject(r.status))}"
"function crumbs(){const c=$('#path');c.innerHTML='';"
" const add=(l,t)=>{const s=document.createElement('span');s.className='crumb';s.textContent=l;s.onclick=()=>{dir=t;load()};c.appendChild(s)};"
" add('/','/');let acc='';"
" dir.split('/').filter(Boolean).forEach((p,i)=>{acc+='/'+p;if(i>0)c.appendChild(document.createTextNode('/'));add(p,acc)})}"
"let cur=[];"
"function load(){crumbs();j('/api/list?dir='+encodeURIComponent(dir)).then(r=>r.json()).then(d=>{cur=d.entries;render()}).catch(e=>log('list failed: '+e))}"
"function render(){const q=($('#q').value||'').toLowerCase();const u=$('#list');u.innerHTML='';"
" if(dir!=='/'){const li=document.createElement('li');li.innerHTML=\"<span class='n d'>.. (up)</span>\";li.onclick=()=>{dir=dir.replace(/\\/[^/]*\\/?$/,'')||'/';$('#q').value='';load()};u.appendChild(li)}"
" cur.filter(e=>!q||e.name.toLowerCase().includes(q)).sort((a,b)=>(b.dir-a.dir)||a.name.localeCompare(b.name)).forEach(e=>{"
"  const li=document.createElement('li');"
"  const nm=document.createElement('span');nm.className='n'+(e.dir?' d':'');nm.textContent=(e.dir?'\\uD83D\\uDCC1 ':'')+e.name;"
"  if(e.dir)nm.onclick=()=>{dir=(dir==='/'?'':dir)+'/'+e.name;$('#q').value='';load()};"
"  li.appendChild(nm);"
"  if(!e.dir){const s=document.createElement('span');s.className='sz';s.textContent=(e.size|0)+' B';li.appendChild(s);"
"   const b=document.createElement('button');b.textContent='delete';"
"   b.onclick=()=>{if(confirm('Delete '+e.name+'?'))del((dir==='/'?'':dir)+'/'+e.name)};li.appendChild(b)}"
"  u.appendChild(li)})}"
"function del(p){j('/api/delete?path='+encodeURIComponent(p),{method:'POST'}).then(()=>{log('deleted '+p);load()}).catch(e=>log('delete failed: '+e))}"
"function mkdir(){const n=prompt('New folder name');if(!n)return;const p=(dir==='/'?'':dir)+'/'+n;"
" j('/api/mkdir?path='+encodeURIComponent(p),{method:'POST'}).then(()=>{log('created '+n);load()}).catch(e=>log('mkdir failed: '+e))}"
"function up(files){let i=0;(function nx(){if(i>=files.length){load();return}const f=files[i++];"
" const p=(dir==='/'?'':dir)+'/'+f.name;log('uploading '+f.name+' ...');"
" j('/api/upload?path='+encodeURIComponent(p),{method:'POST',body:f}).then(()=>{log('ok '+f.name);nx()}).catch(e=>{log('FAIL '+f.name+': '+e);nx()})})()}"
"function log(m){$('#log').textContent=m}"
"$('#mk').onclick=mkdir;"
"$('#q').oninput=render;"
"$('#pick').onclick=()=>$('#file').click();"
"$('#file').onchange=e=>up(e.target.files);"
"const dz=$('#drop');"
"dz.ondragover=e=>{e.preventDefault();dz.classList.add('hi')};"
"dz.ondragleave=()=>dz.classList.remove('hi');"
"dz.ondrop=e=>{e.preventDefault();dz.classList.remove('hi');up(e.dataTransfer.files)};"
"load();"
"</script></main>";

// ---- routing ----------------------------------------------------------------

static bool build_listing(conn_t *c) {
    char dir[300];
    if (!query_param(c, "dir", dir, sizeof dir) || !dir[0]) strcpy(dir, "/");
    static FATFS fs; f_mount(&fs, "", 1);
    DIR d; FILINFO fi;
    if (f_opendir(&d, dir) != FR_OK) return respond_text(c, 404, "opendir failed");
    uint32_t cap = 8192, len = 0;
    char *out = malloc(cap);
    if (!out) { f_closedir(&d); close_conn(c); return true; }
    len += snprintf(out + len, cap - len, "{\"dir\":\"%s\",\"entries\":[", dir);
    bool first = true;
    while (f_readdir(&d, &fi) == FR_OK && fi.fname[0]) {
        if (len > cap - 300) break;
        len += snprintf(out + len, cap - len, "%s{\"name\":\"%s\",\"dir\":%s,\"size\":%lu}",
                        first ? "" : ",", fi.fname, (fi.fattrib & AM_DIR) ? "true" : "false",
                        (unsigned long)fi.fsize);
        first = false;
    }
    f_closedir(&d);
    len += snprintf(out + len, cap - len, "]}");
    bool closed = respond(c, 200, "application/json", out, len);
    free(out);
    return closed;
}

// Parse the completed request headers and route. Returns true if it responded and
// closed the connection; false if a body is still expected (upload in progress).
static bool handle_headers(conn_t *c) {
    c->is_post = strncmp(c->hdr, "POST ", 5) == 0;
    const char *sp = strchr(c->hdr, ' ');
    if (!sp) return respond_text(c, 400, "bad request");
    const char *ps = sp + 1, *pe = strchr(ps, ' ');
    int pl = pe ? (int)(pe - ps) : 0;
    if (pl <= 0 || pl >= (int)sizeof c->path) return respond_text(c, 400, "bad path");
    memcpy(c->path, ps, pl); c->path[pl] = 0;
    const char *cl = strstr(c->hdr, "Content-Length:");
    if (!cl) cl = strstr(c->hdr, "content-length:");
    c->content_len = cl ? (uint32_t)strtoul(cl + 15, NULL, 10) : 0;

    if (strncmp(c->path, "/api/list", 9) == 0) return build_listing(c);

    if (strncmp(c->path, "/api/upload", 11) == 0 && c->is_post) {
        char p[300];
        if (!query_param(c, "path", p, sizeof p) || !p[0]) return respond_text(c, 400, "no path");
        if (g_uploading) return respond_text(c, 500, "busy");
        static FATFS fs; f_mount(&fs, "", 1);
        if (f_open(&g_upfile, p, FA_WRITE | FA_CREATE_ALWAYS) != FR_OK) return respond_text(c, 500, "open failed");
        g_uploading = true; c->mine_upload = true; c->body_recv = 0;
        cdc_printf("http: upload %s (%lu bytes)\r\n", p, (unsigned long)c->content_len);
        if (c->content_len == 0) {
            f_close(&g_upfile); g_uploading = false; c->mine_upload = false;
            return respond_text(c, 200, "ok");
        }
        return false;   // body streams in
    }

    if (strncmp(c->path, "/api/delete", 11) == 0 && c->is_post) {
        char p[300];
        if (!query_param(c, "path", p, sizeof p) || !p[0]) return respond_text(c, 400, "no path");
        static FATFS fs; f_mount(&fs, "", 1);
        FRESULT r = f_unlink(p);
        return respond_text(c, r == FR_OK ? 200 : 500, r == FR_OK ? "ok" : "delete failed");
    }

    if (strncmp(c->path, "/api/mkdir", 10) == 0 && c->is_post) {
        char p[300];
        if (!query_param(c, "path", p, sizeof p) || !p[0]) return respond_text(c, 400, "no path");
        static FATFS fs; f_mount(&fs, "", 1);
        FRESULT r = f_mkdir(p);
        return respond_text(c, r == FR_OK ? 200 : 500, r == FR_OK ? "ok" : "mkdir failed");
    }

    if (strcmp(c->path, "/") == 0) return respond(c, 200, "text/html", PAGE, sizeof PAGE - 1);
    return respond_text(c, 404, "not found");
}

static bool finish_upload(conn_t *c) {
    f_close(&g_upfile);
    g_uploading = false; c->mine_upload = false;
    cdc_printf("http: upload done (%lu bytes)\r\n", (unsigned long)c->body_recv);
    return respond_text(c, 200, "ok");
}

// Feed a run of received bytes through the header/body state machine.
// Returns false if the connection was closed (caller must stop touching it).
static bool process_bytes(conn_t *c, const uint8_t *d, uint16_t len) {
    uint16_t i = 0;
    if (!c->hdr_done) {
        while (i < len && !c->hdr_done) {
            if (c->hdrlen < (int)sizeof(c->hdr) - 1) c->hdr[c->hdrlen++] = (char)d[i];
            i++;
            if (c->hdrlen >= 4 && memcmp(c->hdr + c->hdrlen - 4, "\r\n\r\n", 4) == 0) {
                c->hdr[c->hdrlen] = 0; c->hdr_done = true;
                if (handle_headers(c)) return false;   // responded + closed
            }
        }
    }
    if (c->hdr_done && c->mine_upload && g_uploading && i < len) {
        UINT wr = 0;
        f_write(&g_upfile, d + i, len - i, &wr);
        c->body_recv += wr;
        if (c->body_recv >= c->content_len) return !finish_upload(c) ? true : false;
    }
    return true;
}

// ---- lwIP callbacks ---------------------------------------------------------

static err_t on_sent(void *arg, struct tcp_pcb *pcb, u16_t l) {
    (void)pcb; (void)l;
    conn_t *c = arg;
    if (c && c->resp) push_send(c);
    return ERR_OK;
}
static void on_err(void *arg, err_t e) {
    (void)e;
    conn_t *c = arg;
    if (!c) return;
    if (c->mine_upload && g_uploading) { f_close(&g_upfile); g_uploading = false; }
    if (c->resp) free(c->resp);
    free(c);   // pcb already freed by lwIP
}
static err_t on_recv(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err) {
    conn_t *c = arg;
    if (!p) { close_conn(c); return ERR_OK; }
    if (err != ERR_OK) { pbuf_free(p); return err; }
    tcp_recved(pcb, p->tot_len);
    bool alive = true;
    for (struct pbuf *q = p; q && alive; q = q->next)
        alive = process_bytes(c, q->payload, q->len);
    pbuf_free(p);
    return ERR_OK;
}
static err_t on_accept(void *arg, struct tcp_pcb *pcb, err_t err) {
    (void)arg;
    if (err != ERR_OK || !pcb) return ERR_VAL;
    conn_t *c = calloc(1, sizeof *c);
    if (!c) return ERR_MEM;
    c->pcb = pcb;
    tcp_arg(pcb, c);
    tcp_recv(pcb, on_recv);
    tcp_sent(pcb, on_sent);
    tcp_err(pcb, on_err);
    return ERR_OK;
}

void httpsrv_start(void) {
    if (s_listen) return;
    struct tcp_pcb *pcb = tcp_new();
    if (!pcb) return;
    if (tcp_bind(pcb, IP_ANY_TYPE, 80) != ERR_OK) { tcp_close(pcb); return; }
    s_listen = tcp_listen(pcb);
    if (!s_listen) { tcp_close(pcb); return; }
    tcp_accept(s_listen, on_accept);
    cdc_printf("http: listening on :80\r\n");
}
void httpsrv_stop(void) {
    if (!s_listen) return;
    tcp_close(s_listen);
    s_listen = NULL;
    cdc_printf("http: stopped\r\n");
}
#endif // HAVE_WIFI

"""Offline mitmproxy tnetstring reader. Never connects to the captured service."""
from pathlib import Path
import json, re, zlib, gzip
from urllib.parse import urlsplit, parse_qs

def parse(data, pos=0):
    colon=data.index(b':',pos)
    size=int(data[pos:colon]); start=colon+1; end=start+size
    payload=data[start:end]; kind=data[end:end+1]; nxt=end+1
    if kind==b',': return payload, nxt
    if kind==b';': return payload.decode('utf-8'), nxt
    if kind==b'#': return int(payload),nxt
    if kind==b'^': return float(payload),nxt
    if kind==b'!': return payload==b'true',nxt
    if kind==b'~': return None,nxt
    if kind in (b']',b'}'):
        result=[]; i=start
        while i<end:
            value,i=parse(data,i); result.append(value)
        return (dict(zip(result[::2],result[1::2])) if kind==b'}' else result),nxt
    raise ValueError(kind)

def read_flows(path):
    data=Path(path).read_bytes(); pos=0
    while pos<len(data):
        flow,pos=parse(data,pos)
        def decode(value, key=None):
            if isinstance(value,bytes): return value if key=='content' else value.decode('utf-8',errors='replace')
            if isinstance(value,dict): return {decode(k):decode(v,decode(k)) for k,v in value.items()}
            if isinstance(value,list): return [decode(v) for v in value]
            return value
        yield decode(flow)

def safe_path(path):
    path=urlsplit(path).path
    path=re.sub(r'eyJ[A-Za-z0-9_.-]+', '{token}', path)
    path=re.sub(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', '{mac}', path)
    path=re.sub(r'cam[0-9]+', 'cam{id}', path)
    path=re.sub(r'(?<=/)[0-9a-fA-F]{16}(?=/|$)', '{device}', path)
    path=re.sub(r'[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}', '{uuid}', path)
    path=re.sub(r'(?<=/)[0-9]+(?=/|$)', '{id}', path)
    path=re.sub(r'(?<=/)[0-9a-fA-F]{20,}(?=/|$)', '{hash}', path)
    return path
def body(message):
    content=message.get('content',b'') or b''
    headers=dict((k.lower(),v) for k,v in message.get('headers',[]))
    if headers.get('content-encoding')=='gzip': content=gzip.decompress(content)
    return content.decode('utf-8',errors='replace')

if __name__=='__main__':
    import sys,collections
    flows=list(read_flows(sys.argv[1])); print('flows',len(flows))
    stats=collections.Counter()
    for f in flows:
        q=f.get('request') or {}; r=f.get('response') or {}
        if q: stats[(q.get('method'),q.get('host'),safe_path(q.get('path','')),r.get('status_code'))]+=1
    for key,count in stats.items(): print(count,*key)

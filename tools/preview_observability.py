#!/usr/bin/env python3
"""Loopback-only disposable Grafana/Prometheus fixture preview; never a deployed monitoring service."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import argparse,hashlib,json,math,os,re,secrets,socket,subprocess,sys,threading,time,urllib.request,urllib.parse,urllib.error,uuid

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'target'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tools-metadata',type=Path,default=TARGET/'dashboard-preview-tools.json')
parser.add_argument('--sample-root',type=Path,required=True)
parser.add_argument('--source-sha',required=True)
args=parser.parse_args()
TOOLS=json.loads(args.tools_metadata.read_text())
assert TOOLS['grafanaVersion']=='13.2.4' and TOOLS['prometheusVersion']=='3.15.0'
SOURCE=args.source_sha
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==SOURCE
assert subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)==b''
SESSION=TARGET/('dashboard-preview-'+uuid.uuid4().hex[:12]);SESSION.mkdir();assert SESSION.resolve().is_relative_to(TARGET.resolve())
(TARGET/'dashboard-preview-active.json').write_text(json.dumps({'session':str(SESSION)},indent=2))
samples=args.sample_root.resolve()
def sample(name):
    paths=list(samples.rglob(name));assert len(paths)==1
    return paths[0].read_text(encoding='utf-8')
healthy=sample('http-metrics.prom');initial=sample('metrics-initial.prom')
stale,count=re.subn(r'(?m)^agentic_outbox_observation_age_seconds [^\r\n]+','agentic_outbox_observation_age_seconds 200.0',healthy);assert count==1
expected_healthy={k:float(v) for k,v in re.findall(r'(?m)^([a-z_]+) ([^\s]+)$',healthy)};assert len(expected_healthy)==5
fixtures={'healthy':healthy,'initial':initial,'stale':stale}
state={'mode':'healthy'}
class Metrics(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path!='/q/metrics':self.send_response(404);self.end_headers();return
        mode=state['mode'];body=(fixtures.get(mode,'preview exporter unavailable\n')).encode()
        self.send_response(503 if mode=='exporter-down' else 200);self.send_header('Content-Type','text/plain; version=0.0.4');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
server=ThreadingHTTPServer(('127.0.0.1',0),Metrics);fixture_port=server.server_address[1]
threading.Thread(target=server.serve_forever,daemon=True).start()
def port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
grafana_port=port();prometheus_port=port();grafana_url=f'http://127.0.0.1:{grafana_port}';prometheus_url=f'http://127.0.0.1:{prometheus_port}'
assets=ROOT/'deploy'/'observability';original=json.loads((assets/'outbox-dashboard.json').read_text())
dashboard=json.loads(json.dumps(original).replace('${DS_PROMETHEUS}','preview-prometheus'))
(SESSION/'dashboards').mkdir();(SESSION/'dashboards'/'outbox-dashboard.json').write_text(json.dumps(dashboard))
prov=SESSION/'provisioning'
for name in ['datasources','dashboards','plugins','alerting']:(prov/name).mkdir(parents=True)
(prov/'datasources'/'preview.yaml').write_text(json.dumps({'apiVersion':1,'datasources':[{'name':'Local synthetic metrics','uid':'preview-prometheus','type':'prometheus','access':'proxy','url':prometheus_url,'isDefault':True,'editable':False,'jsonData':{'httpMethod':'POST','timeInterval':'1s'}}]}))
(prov/'dashboards'/'preview.yaml').write_text(json.dumps({'apiVersion':1,'providers':[{'name':'GPT candidate validation','orgId':1,'type':'file','disableDeletion':True,'updateIntervalSeconds':5,'allowUiUpdates':False,'options':{'path':str(SESSION/'dashboards')}}]}))
config=json.loads((assets/'prometheus.example.json').read_text());config['global']={'scrape_interval':'1s','evaluation_interval':'1s'}
config['scrape_configs'][0]['static_configs'][0]['targets']=[f'127.0.0.1:{fixture_port}']
(SESSION/'prometheus.json').write_text(json.dumps(config));(SESSION/'outbox-rules.json').write_bytes((assets/'outbox-rules.json').read_bytes())
home=Path(TOOLS['grafanaHome']);assert home.resolve().is_relative_to(TARGET.resolve())
for name in ['grafana-data','grafana-logs','grafana-plugins']:(SESSION/name).mkdir()
ini=f'''[paths]
data = {SESSION/'grafana-data'}
logs = {SESSION/'grafana-logs'}
plugins = {SESSION/'grafana-plugins'}
provisioning = {prov}
[server]
http_addr = 127.0.0.1
http_port = {grafana_port}
root_url = {grafana_url}/
[database]
type = sqlite3
path = grafana.db
[security]
admin_user = preview-admin
admin_password = {secrets.token_urlsafe(32)}
secret_key = {secrets.token_urlsafe(32)}
[auth]
disable_login_form = true
[auth.anonymous]
enabled = true
org_name = Main Org.
org_role = Viewer
[users]
allow_sign_up = false
[analytics]
reporting_enabled = false
check_for_updates = false
check_for_plugin_updates = false
[news]
news_feed_enabled = false
[plugins]
preinstall_disabled = true
plugin_admin_enabled = false
[unified_alerting]
enabled = false
[log]
mode = console
level = warn
'''
(SESSION/'grafana.ini').write_text(ini)
# Do not inherit deployment GF_*, DB, cloud credentials, OTEL exporters or proxies.
allowed={'PATH','SYSTEMROOT','WINDIR','SYSTEMDRIVE','TEMP','TMP','USERPROFILE','HOME','APPDATA','LOCALAPPDATA','COMSPEC','PROCESSOR_ARCHITECTURE','NUMBER_OF_PROCESSORS'}
env={k:v for k,v in os.environ.items() if k.upper() in allowed};env['NO_PROXY']='127.0.0.1,localhost'
children=[];logs=[];prometheus=None
report={'sourceSha':SOURCE,'tools':TOOLS,'session':str(SESSION),'grafanaUrl':grafana_url,'prometheusUrl':prometheus_url,'fixtureTarget':f'127.0.0.1:{fixture_port}',
        'scope':'Local Grafana/Prometheus preview of recorded synthetic CI metrics; no shared database, deployed system, SLO or caller authority',
        'sourceDashboardSha256':hashlib.sha256((assets/'outbox-dashboard.json').read_bytes()).hexdigest(),
        'boundDashboardSha256':hashlib.sha256((SESSION/'dashboards'/'outbox-dashboard.json').read_bytes()).hexdigest(),
        'fixtureSha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in fixtures.items()},'cases':[]}
def save():(SESSION/'validation.json').write_text(json.dumps(report,indent=2))
def launch(name,args):
    log=(SESSION/(name+'-'+str(len(children))+'.log')).open('wb');logs.append(log)
    process=subprocess.Popen(args,cwd=SESSION,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    children.append(process);report['ownedChildPids']=[p.pid for p in children];save();return process
def get_json(url,body=None):
    request=urllib.request.Request(url,data=json.dumps(body).encode() if body is not None else None,
                                   headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=10) as response:return json.load(response)
def ready(url,process):
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        if process.poll() is not None:raise RuntimeError('Owned preview process exited: '+str(process.returncode))
        try:
            with urllib.request.urlopen(url,timeout=2) as response:
                if response.status==200:return
        except (urllib.error.URLError,TimeoutError):pass
        time.sleep(.5)
    raise RuntimeError('Preview readiness not established; inspect retained process/log state')
def start_prometheus():
    process=launch('prometheus',[str(TARGET/('prometheus.exe' if os.name=='nt' else 'prometheus')),'--config.file='+str(SESSION/'prometheus.json'),
        '--storage.tsdb.path='+str(SESSION/'prometheus-data'),'--web.listen-address=127.0.0.1:'+str(prometheus_port),
        '--storage.tsdb.retention.time=1h','--log.level=warn'])
    ready(prometheus_url+'/-/ready',process);return process
def panel_values():
    now=int(time.time()*1000);queries=[]
    for panel in dashboard['panels']:
        queries.append({'refId':str(panel['id']),'datasource':{'uid':'preview-prometheus','type':'prometheus'},
            'expr':panel['targets'][0]['expr'].replace('$job','open-workflow-agentic').replace('$instance','.*'),
            'format':'time_series','instant':True,'range':False,'intervalMs':1000,'maxDataPoints':1000})
    response=get_json(grafana_url+'/api/ds/query',{'from':str(now-60000),'to':str(now),'queries':queries})
    values={}
    for panel in dashboard['panels']:
        result=response['results'][str(panel['id'])]
        if result.get('error') or result.get('status',200)>=400:raise RuntimeError('Grafana query failed: '+str(result.get('error')))
        points=[]
        for frame in result.get('frames',[]):
            for i,field in enumerate(frame['schema']['fields']):
                if field['type']=='number':points.extend(v for v in frame['data']['values'][i] if isinstance(v,(int,float)) and math.isfinite(v))
        values[str(panel['id'])]=points
    return values,response
def expected(mode):
    if mode=='healthy':return {'1':[1.0],'2':[1.0],'3':[expected_healthy['agentic_outbox_observation_age_seconds']],
        '4':[expected_healthy['agentic_outbox_unpublished']],'5':[expected_healthy['agentic_outbox_unpublished_oldest_age_seconds']],
        '6':[expected_healthy['agentic_outbox_failed']]}
    if mode=='initial':return {'1':[1.0],'2':[0.0],'3':[],'4':[],'5':[],'6':[]}
    if mode=='stale':return {'1':[1.0],'2':[1.0],'3':[200.0],'4':[],'5':[],'6':[]}
    if mode=='exporter-down':return {'1':[0.0],'2':[],'3':[],'4':[],'5':[],'6':[]}
    raise ValueError(mode)
def verify(mode):
    started=int(time.time()*1000);state['mode']=mode
    wanted=expected(mode);deadline=time.monotonic()+30;last_error='not evaluated'
    while time.monotonic()<deadline:
        try:
            values,response=panel_values()
            if values==wanted:
                path=SESSION/('queries-'+mode+'-'+str(len(report['cases']))+'.json');path.write_text(json.dumps(response,indent=2))
                row={'mode':mode,'passed':True,'values':values,'startedAt':started,'verifiedAt':int(time.time()*1000),
                    'dashboardUrl':grafana_url+'/d/agentic-outbox-v1/agentic-outbox-observations?orgId=1&from='+str(started+1500)+'&to=now&refresh=5s',
                    'queryResponseSha256':hashlib.sha256(path.read_bytes()).hexdigest()}
                report['cases'].append(row);save();print(json.dumps(row),flush=True);return
            last_error=str(values)
        except (urllib.error.URLError,TimeoutError,RuntimeError,KeyError) as e:last_error=str(e)
        time.sleep(.5)
    raise RuntimeError('Actual Grafana panel values did not match '+mode+': '+last_error)
try:
    prometheus=start_prometheus()
    grafana=launch('grafana',[str(home/'bin'/('grafana.exe' if os.name=='nt' else 'grafana')),'server','--homepath',str(home),'--config',str(SESSION/'grafana.ini')])
    ready(grafana_url+'/api/health',grafana)
    health=get_json(grafana_url+'/api/health');assert health['version']=='13.2.4' and health['database']=='ok'
    build=get_json(prometheus_url+'/api/v1/status/buildinfo');assert build['data']['version']=='3.15.0'
    report['grafanaHealth']=health;report['prometheusBuild']=build['data']
    imported=get_json(grafana_url+'/api/dashboards/uid/agentic-outbox-v1')
    (SESSION/'imported-dashboard.json').write_text(json.dumps(imported,indent=2))
    actual=imported['dashboard'];assert actual['uid']==dashboard['uid'] and len(actual['panels'])==6
    assert [(p['title'],p['targets'][0]['expr']) for p in actual['panels']]==[(p['title'],p['targets'][0]['expr']) for p in dashboard['panels']]
    report['provisioningAccepted']=True;report['importedSchemaVersion']=actual['schemaVersion'];save()
    verify('healthy')
    print('PREVIEW_READY: healthy, initial, stale, exporter-down, collector-down, recover or stop',flush=True)
    for line in sys.stdin:
        mode=line.strip()
        try:
            if mode=='stop':break
            elif mode=='collector-down':
                prometheus.terminate();prometheus.wait(timeout=15);assert prometheus.poll() is not None
                try:panel_values();raise AssertionError('Unavailable collector produced successful values')
                except urllib.error.HTTPError as error:
                    detail=json.loads(error.read());results=detail.get('results',{})
                    assert len(results)==6 and all('refused' in str(v.get('error','')).lower() or 'connectex' in str(v.get('error','')).lower() for v in results.values()),detail
                    assert get_json(grafana_url+'/api/health')['database']=='ok'
                    error_path=SESSION/'collector-error.json';error_path.write_text(json.dumps(detail,indent=2))
                    row={'mode':mode,'passed':True,'collectorExit':prometheus.returncode,'httpStatus':error.code,
                         'allSixQueriesFailedWithConnectionRefusal':True,'errorResponseSha256':hashlib.sha256(error_path.read_bytes()).hexdigest(),'verifiedAt':int(time.time()*1000)}
                    report['cases'].append(row);save();print(json.dumps(row),flush=True)
            elif mode=='recover':
                if prometheus.poll() is not None:prometheus=start_prometheus()
                verify('healthy')
            elif mode in fixtures or mode=='exporter-down':verify(mode)
            else:print('Unknown preview command',flush=True)
        except Exception as error:
            report['cases'].append({'mode':mode,'passed':False,'error':str(error)});save();print(json.dumps(report['cases'][-1]),flush=True)
finally:
    server.shutdown();server.server_close()
    for child in reversed(children):
        if child.poll() is None:
            child.terminate()
            try:child.wait(timeout=15)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
    for log in logs:log.close()
    report['allOwnedChildrenStopped']=all(p.poll() is not None for p in children)
    report['childExitCodes']=[p.returncode for p in children];save()
    print(json.dumps({'cleanup':report['allOwnedChildrenStopped'],'session':str(SESSION)}),flush=True)

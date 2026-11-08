import json, subprocess, sys, tempfile, unittest, zipfile
from pathlib import Path
SDK=Path(__file__).resolve().parents[1]
CLI=SDK/'remrss_mod.py'

def run(*args, ok=True):
    p=subprocess.run([sys.executable,str(CLI),*map(str,args)],text=True,capture_output=True)
    if ok and p.returncode != 0: raise AssertionError(p.stderr or p.stdout)
    if not ok and p.returncode == 0: raise AssertionError('expected command failure')
    return p

def minimal_scenes(project):
    (project/'settings.ui.json').write_text(json.dumps({'scene':'settings','root':{'type':'ui.text','value':'Settings'}}))
    (project/'rear.ui.json').write_text(json.dumps({'scene':'rear','root':{'type':'ui.text','value':'Counter'}}))
    (project/'tests'/'config-changes.json').unlink(missing_ok=True)

class ModToolTests(unittest.TestCase):
    def test_templates_validate_without_runtime_for_info(self):
        with tempfile.TemporaryDirectory() as td:
            blank=Path(td)/'blank'; info=Path(td)/'info'
            run('create','hostless.blank','--out',blank)
            run('check',blank); run('test',blank)
            blank_manifest=json.loads((blank/'manifest.json').read_text())
            self.assertEqual(blank_manifest['data']['stateSchema']['properties'],{})
            self.assertEqual([cap['name'] for cap in blank_manifest['capabilities']['required']],['rear.scene'])
            self.assertEqual([cap['name'] for cap in blank_manifest['capabilities']['optional']],['rear.scene.keep_visible'])
            run('create','hostless.info','--template','info','--out',info)
            manifest=json.loads((info/'manifest.json').read_text())
            self.assertEqual(manifest['runtime']['kind'],'none')
            self.assertEqual({cap['name'] for cap in manifest['capabilities']['required']},{'rear.scene','data.battery'})
            self.assertEqual(manifest['data']['stateSchema']['properties'],{})
            run('check',info); run('test',info)
            counter=Path(td)/'counter'
            run('create','hostless.counter','--template','counter','--out',counter)
            self.assertEqual(json.loads((counter/'manifest.json').read_text())['minimumAndroidApi'],29)
            run('check',counter)
            counter_manifest=json.loads((counter/'manifest.json').read_text())
            self.assertEqual([cap['name'] for cap in counter_manifest['capabilities']['required']],['rear.scene'])
            counter_manifest['minimumAndroidApi']=24
            (counter/'manifest.json').write_text(json.dumps(counter_manifest))
            self.assertIn('至少为 29',run('check',counter,ok=False).stderr)

    def test_counter_pack_sign_and_tamper_detection(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); project=root/'counter'; key=root/'author.pem'; archive=root/'counter.zip'
            run('create','sample.counter','--template','counter','--out',project)
            run('test',project)
            subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            run('pack',project,'--key',key,'--out',archive)
            run('check',archive)
            tampered=root/'tampered.zip'
            with zipfile.ZipFile(archive) as source, zipfile.ZipFile(tampered,'w',zipfile.ZIP_DEFLATED) as out:
                for item in source.infolist():
                    value=source.read(item.filename)
                    if item.filename == 'main.js': value += b'\n// changed after signing\n'
                    out.writestr(item.filename,value)
            self.assertIn('完整性校验失败',run('check',tampered,ok=False).stderr)

    def test_unknown_capability_version_and_path_traversal_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'project'
            run('create','sample.invalid','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            manifest['capabilities']['optional']=[{'name':'rear.scene.keep_visible','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            run('check',project)
            manifest['capabilities']['optional']=[{'name':'rear.keepVisible','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('不认识能力',run('check',project,ok=False).stderr)
            manifest['capabilities']['optional']=[{'name':'rear.officialTheme','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('不认识能力',run('check',project,ok=False).stderr)
            manifest['capabilities']['optional']=[{'name':'rear.cast','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('不认识能力',run('check',project,ok=False).stderr)
            manifest['capabilities']['optional']=[]
            manifest['contributions']={'officialThemes':[]}
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('未知字段',run('check',project,ok=False).stderr)
            manifest.pop('contributions')
            manifest['capabilities']['optional']=[{'name':'clock.snapshot','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('不认识能力',run('check',project,ok=False).stderr)
            manifest['capabilities']['optional']=[]
            manifest['capabilities']['required']=[{'name':'rear.scene','minimumVersion':9}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('不支持请求版本',run('check',project,ok=False).stderr)
            manifest['capabilities']['required']=[]
            manifest['scenes']['settings']='../evil.ui.json'
            (project/'manifest.json').write_text(json.dumps(manifest))
            self.assertIn('越界路径',run('check',project,ok=False).stderr)

    def test_system_metrics_bindings_are_limited_to_live_provider_fields(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'project'
            run('create','sample.metrics','--template','blank','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            manifest['capabilities']['optional']=[{'name':'data.systemMetrics','minimumVersion':1}]
            (project/'manifest.json').write_text(json.dumps(manifest))
            scene=json.loads((project/'rear.ui.json').read_text())
            scene['root']={'type':'ui.text','value':{'bind':'data.systemMetrics.cpu.utilizationPercent'}}
            (project/'rear.ui.json').write_text(json.dumps(scene))
            run('check',project)
            scene['root']['value']['bind']='data.systemMetrics.gpu.utilizationPercent'
            (project/'rear.ui.json').write_text(json.dumps(scene))
            self.assertIn('宿主未提供此数据字段',run('check',project,ok=False).stderr)

    def test_reducer_cases_use_schema_defaults_and_validated_config_overrides(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.config-counter','--template','counter','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            manifest['data']['configSchema']={
                'type':'object',
                'properties':{
                    'start':{'type':'integer','minimum':0,'maximum':100,'default':4},
                    'step':{'type':'integer','minimum':1,'maximum':10,'default':2},
                },
                'required':['step'],
                'additionalProperties':False,
            }
            manifest['data']['stateSchema']={
                'type':'object','properties':{'count':{'type':'integer','minimum':0,'maximum':1000}},
                'required':['count'],'additionalProperties':False,
            }
            minimal_scenes(project)
            (project/'manifest.json').write_text(json.dumps(manifest))
            (project/'main.js').write_text('''globalThis.__remrss = {
  init({ config }) { return { count: config.start }; },
  reduce({ state, config, event }) {
    return event.type === "counter.increment"
      ? { state: { count: state.count + config.step } }
      : { state };
  }
};
''')
            cases=[
                {'name':'schema defaults reach init and reduce','events':[{'type':'counter.increment'}],'expected':{'count':6}},
                {'name':'case config overrides defaults','config':{'start':10,'step':3},'events':[{'type':'counter.increment'}],'expected':{'count':13}},
                {'name':'partial config keeps other defaults','config':{'step':5},'events':[{'type':'counter.increment'}],'expected':{'count':9}},
            ]
            (project/'tests'/'cases.json').write_text(json.dumps(cases))
            run('test',project)
            cases[1]['config']={'start':10,'step':0}
            (project/'tests'/'cases.json').write_text(json.dumps(cases))
            self.assertIn('超出范围',run('test',project,ok=False).stderr)
            cases[1]['config']={'unknown':10}
            (project/'tests'/'cases.json').write_text(json.dumps(cases))
            self.assertIn('含 schema 未声明字段',run('test',project,ok=False).stderr)

    def test_config_change_cases_use_old_and_new_config_and_reject_host_event_spoofing(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.config-change','--template','counter','--out',project)
            (project/'tests'/'config-changes.json').write_text(json.dumps([{
                'name':'保存步长后派生显示字段',
                'state':{'count':7,'stepUsed':1,'previousStep':1},
                'previousConfig':{'step':1},
                'config':{'step':4},
                'expected':{
                    'state':{'count':7,'stepUsed':4,'previousStep':1},
                    'config':{'step':4},
                },
            }]))
            run('test',project)
            ordinary=json.loads((project/'tests'/'cases.json').read_text())
            ordinary[0]['events']=[{'type':'host.configChanged'}]
            (project/'tests'/'cases.json').write_text(json.dumps(ordinary))
            self.assertIn('不得伪造 host.configChanged',run('test',project,ok=False).stderr)
            ordinary[0]['events']=[]
            (project/'tests'/'cases.json').write_text(json.dumps(ordinary))
            invalid=json.loads((project/'tests'/'config-changes.json').read_text())
            invalid[0]['config']={'step':11}
            (project/'tests'/'config-changes.json').write_text(json.dumps(invalid))
            self.assertIn('超出范围',run('test',project,ok=False).stderr)

    def test_config_change_tests_can_assert_effect_intent_without_claiming_host_admission(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.config-effect','--template','counter','--out',project)
            (project/'main.js').write_text('''globalThis.__remrss = {
  init({ config }) { return { count: 0, stepUsed: config.step, previousStep: config.step }; },
  reduce({ state, config, previousConfig, event }) {
    if (event.type === "host.configChanged") {
      return { state: { ...state, stepUsed: config.step, previousStep: previousConfig.step },
        effects: [{ type: "rear.open", scene: "rear", region: "CAMERA_SAFE", keepVisible: false }] };
    }
    return { state };
  }
};
''')
            (project/'tests'/'cases.json').write_text('[]')
            (project/'tests'/'config-changes.json').write_text(json.dumps([{
                'name':'assert config-triggered effect intent',
                'state':{'count':0,'stepUsed':1,'previousStep':1},
                'previousConfig':{'step':1},'config':{'step':2},
                'expected':{
                    'state':{'count':0,'stepUsed':2,'previousStep':1},'config':{'step':2},
                    'effects':[{'type':'rear.open','scene':'rear','region':'CAMERA_SAFE','keepVisible':False}],
                },
            }]))
            run('test',project)

    def test_migration_can_preserve_a_model_that_has_not_been_initialized(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.uninitialized','--template','counter','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            manifest['data']['configSchema']={
                'type':'object',
                'properties':{'team':{'type':'string','maxLength':12,'default':'A'}},
                'required':['team'],
                'additionalProperties':False,
            }
            manifest['data']['stateSchema']={
                'type':'object','properties':{'count':{'type':'integer','minimum':0,'maximum':1000}},
                'required':['count'],'additionalProperties':False,
            }
            minimal_scenes(project)
            manifest['data']['dataVersion']=2
            (project/'manifest.json').write_text(json.dumps(manifest))
            (project/'tests'/'cases.json').write_text('[]')
            (project/'main.js').write_text('''globalThis.__remrss = {
  init({ config }) { return { count: 0, team: config.team }; },
  reduce({ state }) { return { state }; },
  migrate({ state, config }) {
    return state === null
      ? { state: null, config: { team: config.team || 'A' } }
      : { state: { ...state, team: config.team || 'A' }, config: { team: config.team || 'A' } };
  }
};
''')
            (project/'tests'/'migrations.json').write_text(json.dumps([{
                'name':'未激活模型保持未初始化',
                'fromVersion':1,'toVersion':2,'state':None,'config':{},
                'expected':{'state':None,'config':{'team':'A'}},
            }]))
            run('test',project)

    def test_migration_test_rejects_non_null_state_missing_target_fields(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.invalid-migration','--template','counter','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            (project/'main.js').write_text('''globalThis.__remrss = {
  init() { return { count: 0 }; },
  reduce({ state }) { return { state }; },
  migrate({ state }) { return { state: state === null ? null : {}, config: {} }; }
};
''')
            (project/'tests'/'migrations.json').write_text(json.dumps([{
                'name':'缺少新版必需字段', 'fromVersion':1,'toVersion':2,
                'state':{'count':1},'config':{},
                'expected':{'state':{},'config':{}},
            }]))
            failure=run('test',project,ok=False)
            self.assertIn('expected.state.count 是必填字段',failure.stderr)

    def test_migration_requires_explicit_state_and_cannot_clear_existing_state(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'counter'
            run('create','sample.no-state-erasure','--template','counter','--out',project)
            manifest=json.loads((project/'manifest.json').read_text())
            manifest['data']['stateSchema']={
                'type':'object','properties':{'count':{'type':'integer','minimum':0,'maximum':1000}},
                'required':['count'],'additionalProperties':False,
            }
            manifest['data']['configSchema']={'type':'object','properties':{},'required':[],'additionalProperties':False}
            minimal_scenes(project)
            (project/'manifest.json').write_text(json.dumps(manifest))
            (project/'tests'/'cases.json').write_text('[]')
            (project/'main.js').write_text('''globalThis.__remrss = {
  init() { return { count: 0 }; },
  reduce({ state }) { return { state }; },
  migrate({ config }) { return { state: null, config }; }
};
''')
            missing=[{'name':'missing-state','fromVersion':1,'toVersion':2,'config':{},'expected':{'state':None,'config':{}}}]
            (project/'tests'/'migrations.json').write_text(json.dumps(missing))
            self.assertIn('缺失不等于 null',run('test',project,ok=False).stderr)

            object_to_null=[{'name':'drop-state','fromVersion':1,'toVersion':2,'state':{'count':7},'config':{},'expected':{'state':None,'config':{}}}]
            (project/'tests'/'migrations.json').write_text(json.dumps(object_to_null))
            self.assertIn('已有 state 不能被迁移清空',run('test',project,ok=False).stderr)

            object_output=[{'name':'drop-state-at-runtime','fromVersion':1,'toVersion':2,'state':{'count':7},'config':{},'expected':{'state':{'count':7},'config':{}}}]
            (project/'tests'/'migrations.json').write_text(json.dumps(object_output))
            self.assertIn('cannot discard an initialized state',run('test',project,ok=False).stderr)

    def test_ui_rejects_fields_without_a_host_renderer_contract(self):
        with tempfile.TemporaryDirectory() as td:
            project=Path(td)/'sample'
            run('create','sample.ui','--out',project)
            ui=json.loads((project/'settings.ui.json').read_text())
            ui['root']['format']='date'
            (project/'settings.ui.json').write_text(json.dumps(ui))
            self.assertIn('未支持字段',run('check',project,ok=False).stderr)

if __name__=='__main__': unittest.main()

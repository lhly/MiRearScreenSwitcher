#!/usr/bin/env python3
"""Author-side tools for the public ReMRSS Mod ZIP v1 format."""
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path, PurePosixPath

FORMAT = "remrss.mod"
FORMAT_VERSION = 1
MAX_ZIP = 2 * 1024 * 1024
MAX_EXPANDED = 8 * 1024 * 1024
MAX_FILES = 64
MAX_JS = 512 * 1024
MAX_UI = 64 * 1024
TOKEN = re.compile(r"^[a-z][A-Za-z0-9._-]{0,63}$")
SCHEMA_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
CAPABILITY_NAMES = {
    "rear.scene": 1, "rear.scene.keep_visible": 1, "data.battery": 1, "data.systemMetrics": 1,
"model.local": 1, "ui.native": 1, "ui.dialog": 1, "action.invoke": 1, "schedule.visible": 1, "clock.snapshot": 1,
}
DATA_BINDINGS = {
    "data.battery": {"battery.level", "battery.scale", "battery.charging"},
    "data.systemMetrics": {
        "systemMetrics.cpu.utilizationPercent",
        "systemMetrics.memory.availableBytes",
        "systemMetrics.memory.totalBytes",
    },
}
MOD_ID = re.compile(r"^[a-z0-9][a-z0-9_.-]{2,63}$")
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
KNOWN_NODES = {"layout.column", "layout.row", "layout.box", "ui.text", "ui.number", "ui.list", "ui.icon", "ui.deadlineText", "ui.image", "ui.divider", "ui.progress", "ui.button", "settings.row", "settings.input", "settings.action", "settings.number", "settings.toggle", "settings.select", "settings.save", "settings.discard", "ui.confirm"}

class ModError(ValueError): pass

def fail(message: str): raise ModError(message)

def canonical_json(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")

def read_json(path: Path):
    try: return strict_json(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as e: fail(f"{path}: 无法读取 JSON：{e}")

def strict_json(source: str):
    def unique_pairs(pairs):
        result={}
        for key,value in pairs:
            if key in result: fail(f"JSON 含重复字段：{key}")
            result[key]=value
        return result
    return json.loads(source, object_pairs_hook=unique_pairs, parse_constant=lambda value: fail(f"JSON 不允许 {value}"))

def safe_path(value: str, allow_metadata: bool = False) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value: fail("载荷路径无效")
    p = PurePosixPath(value)
    if p.is_absolute() or re.match(r"^[A-Za-z]:", value) or any(part in ("", ".", "..") for part in value.split("/")) or str(p) != value:
        fail(f"拒绝非规范或越界路径：{value}")
    if not allow_metadata and value in ("manifest.json", "manifest.sig", "author.pub"): fail(f"载荷路径保留：{value}")
    return value

def validate_schema(schema, value, field="$", depth=0):
    if depth > 8 or not isinstance(schema, dict): fail(f"{field} schema 深度或类型无效")
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict): fail(f"{field} 应为 object")
        props = schema.get("properties", {})
        if not isinstance(props, dict): fail(f"{field}.properties 应为 object")
        required = schema.get("required", [])
        if not isinstance(required, list) or any(not isinstance(k, str) for k in required): fail(f"{field}.required 无效")
        additional = schema.get("additionalProperties", False)
        if not additional and set(value) - set(props): fail(f"{field} 含 schema 未声明字段")
        for key in required:
            if key not in value: fail(f"{field}.{key} 是必填字段")
        for key, child in value.items():
            if key in props: validate_schema(props[key], child, f"{field}.{key}", depth + 1)
    elif kind == "array":
        if not isinstance(value, list): fail(f"{field} 应为 array")
        if len(value) < int(schema.get("minItems", 0)) or len(value) > int(schema.get("maxItems", 128)): fail(f"{field} 数组超限")
        for i, child in enumerate(value): validate_schema(schema.get("items", {}), child, f"{field}[{i}]", depth + 1)
    elif kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int): fail(f"{field} 应为 integer")
        if value < schema.get("minimum", -2**53) or value > schema.get("maximum", 2**53): fail(f"{field} 超出范围")
    elif kind == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not (-1e12 <= value <= 1e12): fail(f"{field} 应为有限 number")
    elif kind == "string":
        if not isinstance(value, str) or len(value) > int(schema.get("maxLength", 4096)): fail(f"{field} 应为有界 string")
        if "pattern" in schema and (schema["pattern"] != r"^[a-f0-9]{48}$" or not re.fullmatch(r"[a-f0-9]{48}", value)): fail(f"{field} opaque 动作引用格式无效")
    elif kind == "boolean":
        if not isinstance(value, bool): fail(f"{field} 应为 boolean")
    else: fail(f"{field} schema 类型不支持：{kind}")
    if "enum" in schema and value not in schema["enum"]: fail(f"{field} 不在 enum 范围")
    if "const" in schema and value != schema["const"]: fail(f"{field} 与 const 不符")

def validate_schema_definition(schema, field, depth=0):
    if depth > 8 or not isinstance(schema, dict) or set(schema)-{"type","properties","required","additionalProperties","items","minimum","maximum","maxLength","pattern","minItems","maxItems","enum","const","default"}:
        fail(f"{field} schema 深度或字段无效")
    kind=schema.get("type")
    if kind not in ("object","array","integer","number","string","boolean"): fail(f"{field} schema 类型无效")
    if kind == "object":
        props=schema.get("properties",{})
        if not isinstance(props,dict) or len(props)>64 or any(not SCHEMA_KEY.fullmatch(k) for k in props): fail(f"{field}.properties 最多 64 个合法字段")
        required=schema.get("required",[])
        if not isinstance(required,list) or len(required)>len(props) or any(not isinstance(k,str) or k not in props for k in required) or len(set(required))!=len(required): fail(f"{field}.required 无效")
        if field == "configSchema" and any("default" not in props[k] for k in required): fail(f"{field}.required 配置字段必须声明 default")
        if schema.get("additionalProperties",False) is not False: fail(f"{field}.additionalProperties 首版必须为 false")
        for name,child in props.items(): validate_schema_definition(child,f"{field}.{name}",depth+1)
    elif kind == "array":
        maximum=schema.get("maxItems")
        minimum=schema.get("minItems",0)
        if isinstance(maximum,bool) or isinstance(minimum,bool) or not isinstance(minimum,int) or not isinstance(maximum,int) or not 0<=minimum<=maximum<=128: fail(f"{field}.items 范围必须为 0..128")
        validate_schema_definition(schema.get("items"),f"{field}[]",depth+1)
    elif kind in ("integer","number"):
        lo=schema.get("minimum"); hi=schema.get("maximum")
        if isinstance(lo,bool) or isinstance(hi,bool) or not isinstance(lo,(int,float)) or not isinstance(hi,(int,float)) or not (-1e12<=lo<=hi<=1e12): fail(f"{field} 数值范围无效")
    elif kind == "string":
        size=schema.get("maxLength")
        if isinstance(size,bool) or not isinstance(size,int) or not 0<=size<=4096: fail(f"{field}.maxLength 必须为 0..4096")
        if "pattern" in schema and schema["pattern"] != r"^[a-f0-9]{48}$": fail(f"{field}.pattern 不受支持")
    if "enum" in schema:
        choices=schema["enum"]
        if not isinstance(choices,list) or not 1<=len(choices)<=64 or len({json.dumps(x,sort_keys=True) for x in choices})!=len(choices): fail(f"{field}.enum 无效")
    if "const" in schema and not isinstance(schema["const"],(str,int,float,bool,type(None))): fail(f"{field}.const 类型无效")
    if "default" in schema: validate_schema(schema,schema["default"],field+".default")

def schema_has_path(schema, path):
    return schema_at_path(schema,path) is not None

def schema_at_path(schema, path):
    current=schema
    for part in path.split("."):
        current=current.get("properties",{}).get(part)
        if not isinstance(current,dict): return None
    return current

def validate_registered_action_config(config_schema, field):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", field): fail("registered action config 字段必须是顶层字段")
    if field in config_schema.get("required", []): fail("registered action config 字段必须可选，以支持尚未选择动作")
    field_schema=schema_at_path(config_schema,field)
    if not isinstance(field_schema,dict): fail("registered action config 字段未声明")
    props=field_schema.get("properties",{})
    required=field_schema.get("required",[])
    if (field_schema.get("type")!="object" or set(props)!={"id","version"} or
        set(required)!={"id","version"} or len(required)!=2 or field_schema.get("additionalProperties",False) is not False or
        props["id"].get("type")!="string" or props["id"].get("maxLength")!=48 or props["id"].get("pattern")!=r"^[a-f0-9]{48}$" or
        props["version"].get("type")!="integer" or props["version"].get("const")!=1):
        fail("registered action configSchema 必须是可选的opaque id + const 1 version")

def validate_ui(value, state_schema, config_schema, capabilities, runtime_kind):
    if not isinstance(value, dict) or set(value) != {"scene", "root"} or value["scene"] not in ("settings", "rear"):
        fail("UI 文件必须包含 scene=settings|rear 与 root")
    count = 0
    has_config_inputs=False; has_save=False
    states=set(state_schema.get("properties",{})); configs=set(config_schema.get("properties",{}))
    def visit(node, depth):
        nonlocal count
        count += 1
        if count > 128 or depth > 8: fail("UI 节点超过 128 或嵌套超过 8 层")
        if not isinstance(node, dict) or not isinstance(node.get("type"), str): fail("UI node 必须是含 type 的 object")
        typ = node["type"]
        if typ not in KNOWN_NODES: fail(f"未知 UI 组件：{typ}")
        if typ in {"settings.input","settings.action","settings.number","settings.toggle","settings.select"}: has_config_inputs=True
        if typ == "settings.save": has_save=True
        fields={
            "layout.column": {"type","children","gap","horizontalAlignment","verticalAlignment"}, "layout.row": {"type","children","gap","horizontalAlignment","verticalAlignment"}, "layout.box": {"type","children","gap","horizontalAlignment","verticalAlignment"},
            "ui.text": {"type","value","style","format","tone","visibleWhen"}, "ui.number": {"type","value","style","format","tone","visibleWhen"},
            "ui.list": {"type","items","maxItems","style","emptyText","format","visibleWhen"},
            "ui.icon": {"type","name","label","tone","visibleWhen"},
            "ui.deadlineText": {"type","value","format","visibleWhen"},
            "ui.button": {"type","label","onPress","enabled","hint","visibleWhen"}, "ui.image": {"type","asset","alt","visibleWhen"},
            "ui.divider": {"type","visibleWhen"}, "ui.progress": {"type","value","min","max","visibleWhen"},
            "settings.row": {"type","label","value","hint","onPress","visibleWhen"},
            "settings.input": {"type","label","value","hint","placeholder","visibleWhen"},
            "settings.action": {"type","label","value","hint","visibleWhen"},
            "settings.number": {"type","label","value","hint","min","max","visibleWhen"},
            "settings.toggle": {"type","label","value","hint","visibleWhen"},
            "settings.select": {"type","label","value","hint","options","visibleWhen"},
            "settings.save": {"type","visibleWhen"}, "settings.discard": {"type","visibleWhen"},
            "ui.confirm": {"type","label","title","message","onConfirm","visibleWhen"},
        }
        allowed=fields[typ]
        if set(node)-allowed: fail(f"{typ} 包含未支持字段：{sorted(set(node)-allowed)}")
        required={"layout.column":{"children"},"layout.row":{"children"},"layout.box":{"children"},
            "ui.text":{"value"},"ui.number":{"value"},"ui.button":{"label","onPress"},"ui.image":{"asset"},
            "ui.list":{"items","maxItems"},"ui.icon":{"name","label"},"ui.deadlineText":{"value"},
            "ui.divider":set(),"ui.progress":{"value","min","max"},"settings.row":{"label"},
            "settings.input":{"label","value"},"settings.action":{"label","value"},"settings.number":{"label","value","min","max"},
            "settings.toggle":{"label","value"},"settings.select":{"label","value","options"},
            "ui.confirm":{"label","title","message","onConfirm"}, "settings.save":set(), "settings.discard":set()}[typ]
        if required-set(node): fail(f"{typ} 缺少字段：{sorted(required-set(node))}")
        for key in ("value", "label", "hint", "placeholder", "bind", "title", "message", "emptyText"):
            v = node.get(key)
            if key == "value" and isinstance(v, dict):
                if set(v) != {"bind"} or not isinstance(v["bind"], str): fail("value binding 只允许 {bind:path}")
                path=v["bind"]
                if not re.fullmatch(r"(?:state|config|data)\.[A-Za-z][A-Za-z0-9_.]*", path): fail("binding path 格式无效")
                ns, first=path.split(".",1)
                if ns == "state" and first.split(".",1)[0] not in states: fail(f"绑定字段未在 stateSchema 声明：{path}")
                if ns == "config" and first.split(".",1)[0] not in configs: fail(f"绑定字段未在 configSchema 声明：{path}")
                if ns == "state" and not schema_has_path(state_schema, first): fail(f"绑定路径未在 stateSchema 声明：{path}")
                if ns == "config" and not schema_has_path(config_schema, first): fail(f"绑定路径未在 configSchema 声明：{path}")
                if ns == "data":
                    source=first.split(".",1)[0]
                    required={"battery":"data.battery", "systemMetrics":"data.systemMetrics"}.get(source)
                    if required is None or required not in capabilities: fail(f"数据绑定 {path} 未声明对应能力 {required or source}")
                    if first not in DATA_BINDINGS[required]: fail(f"宿主未提供此数据字段：{path}")
            elif key=="value" and isinstance(v,(str,int,float,bool)):
                pass
            elif v is not None and not isinstance(v, (str, int, float, bool)):
                fail(f"{typ}.{key} 类型无效")
        if typ.startswith("layout."):
            horizontal=node.get("horizontalAlignment", "start")
            if horizontal not in (("start","center","end","spaceBetween") if typ=="layout.row" else ("start","center","end")) or node.get("verticalAlignment", "center") not in ("top","center","bottom"):
                fail(f"{typ} 对齐值无效")
        if "format" in node and node["format"] not in ("text","integer","decimal","percent","duration"): fail(f"{typ}.format 无效")
        if "tone" in node and node["tone"] not in ("primary","secondary","success","warning","error"): fail(f"{typ}.tone 无效")
        if "visibleWhen" in node:
            condition=node["visibleWhen"]
            if not isinstance(condition,dict) or set(condition)!={"bind","equals"} or isinstance(condition["equals"],(dict,list)) or condition["equals"] is None: fail("visibleWhen 无效")
            path=condition["bind"]
            if not isinstance(path,str) or not re.fullmatch(r"(?:state|config|data)\.[A-Za-z][A-Za-z0-9_.]*",path): fail("visibleWhen.bind 无效")
            ns,field=path.split(".",1)
            if ns=="state" and not schema_has_path(state_schema,field) or ns=="config" and not schema_has_path(config_schema,field): fail(f"visibleWhen binding 未在 schema 声明：{path}")
            if ns=="data":
                source=field.split(".",1)[0]; capability={"battery":"data.battery","systemMetrics":"data.systemMetrics"}.get(source)
                if capability not in capabilities or field not in DATA_BINDINGS.get(capability,set()): fail(f"visibleWhen 数据绑定未声明：{path}")
        if typ == "ui.list":
            path=node["items"].get("bind") if isinstance(node["items"],dict) else None
            if not isinstance(path,str) or not re.fullmatch(r"(?:state|config)\.[A-Za-z][A-Za-z0-9_.]*",path): fail("ui.list.items 必须绑定 state/config 数组")
            ns,field=path.split(".",1); schema=schema_at_path(state_schema if ns=="state" else config_schema,field)
            if not schema or schema.get("type")!="array" or schema.get("items",{}).get("type") not in ("string","integer","number","boolean"): fail("ui.list.items 必须绑定标量数组")
            if isinstance(node["maxItems"],bool) or not isinstance(node["maxItems"],int) or not 1<=node["maxItems"]<=32: fail("ui.list.maxItems 必须为 1..32")
        if typ == "ui.icon" and node["name"] not in ("add","remove","check","close","timer","trophy","info","warning"): fail("ui.icon.name 未知")
        if typ == "ui.deadlineText":
            path=node.get("value",{}).get("bind") if isinstance(node.get("value"),dict) else None
            if not isinstance(path,str) or not path.startswith(("state.","config.")): fail("ui.deadlineText.value 必须绑定 elapsedRealtime 毫秒截止值")
            ns,field=path.split(".",1); schema=schema_at_path(state_schema if ns=="state" else config_schema,field)
            if not schema or schema.get("type")!="integer" or node.get("format","duration")!="duration": fail("ui.deadlineText 只接受整数毫秒 duration 绑定")
            if "clock.snapshot" not in capabilities: fail("ui.deadlineText 需要声明 clock.snapshot 能力")
        if "style" in node and typ=="ui.list" and node["style"] not in ("body","caption"): fail("ui.list.style 只接受 body/caption")
        if typ in {"settings.input","settings.action","settings.number","settings.toggle","settings.select"}:
            value=node.get("value")
            if not isinstance(value,dict) or not value.get("bind","").startswith("config."): fail(f"{typ}.value 必须绑定 config.*")
            field_schema=schema_at_path(config_schema,value["bind"].removeprefix("config."))
            expected_types={"settings.input":{"string"},"settings.action":{"object"},"settings.number":{"integer","number"},"settings.toggle":{"boolean"},"settings.select":{"string","integer","number","boolean"}}[typ]
            if field_schema is None or field_schema.get("type") not in expected_types: fail(f"{typ} 与 config schema 类型不匹配")
            if typ == "settings.action":
                validate_registered_action_config(config_schema,value["bind"].removeprefix("config."))
                if "action.invoke" not in capabilities: fail("settings.action 需要声明 action.invoke 能力")
        if typ in {"ui.progress","settings.number"}:
            lo=node["min"]; hi=node["max"]
            if isinstance(lo,bool) or isinstance(hi,bool) or not isinstance(lo,(int,float)) or not isinstance(hi,(int,float)) or not lo<hi: fail(f"{typ} 范围无效")
        if typ == "settings.select":
            options=node["options"]
            if not isinstance(options,list) or not 1<=len(options)<=32: fail("settings.select.options 必须在 1..32")
            seen=[]
            for option in options:
                if not isinstance(option,dict) or set(option)!={"label","value"} or not isinstance(option["label"],str) or len(option["label"])>80 or isinstance(option["value"],(dict,list)) or option["value"] is None: fail("settings.select option 无效")
                fingerprint=json.dumps(option["value"],sort_keys=True)
                if fingerprint in seen: fail("settings.select option value 重复")
                seen.append(fingerprint)
        if "gap" in node and (isinstance(node["gap"], bool) or not isinstance(node["gap"], int) or not 0 <= node["gap"] <= 64): fail("UI gap 需在 0..64")
        if typ in {"layout.column", "layout.row", "layout.box"}:
            children = node.get("children")
            if not isinstance(children, list): fail(f"{typ}.children 必须为数组")
            for child in children: visit(child, depth + 1)
        elif "children" in node: fail(f"{typ} 不允许 children")
        if typ in ("ui.button","settings.row","ui.confirm") and "onPress" in node or typ=="ui.confirm":
            action=node.get("onPress",node.get("onConfirm"))
            if not isinstance(action,dict): fail(f"{typ} action 无效")
            if action.get("type")=="event":
                if set(action)!={"type","name"} or not TOKEN.fullmatch(str(action["name"])): fail(f"{typ} event action 无效")
                if runtime_kind!="quickjs": fail("event action 需要 runtime.kind=quickjs")
            elif action.get("type")=="registeredAction":
                if set(action)!={"type","ref"} or "action.invoke" not in capabilities: fail("registeredAction 需要 action.invoke 能力")
                ref=action.get("ref")
                if isinstance(ref,dict) and set(ref)=={"id","version"}:
                    if not isinstance(ref.get("id"),str) or not re.fullmatch(r"[a-f0-9]{48}",ref["id"]) or isinstance(ref.get("version"),bool) or ref.get("version")!=1: fail("registeredAction ref 无效")
                elif isinstance(ref,dict) and set(ref)=={"bind"} and isinstance(ref.get("bind"),str) and ref["bind"].startswith("config."):
                    validate_registered_action_config(config_schema,ref["bind"].removeprefix("config."))
                else: fail("registeredAction ref 必须是登记opaque引用或config.*顶层绑定")
            else: fail(f"{typ} action 类型未知")
        if typ == "ui.image":
            asset = safe_path(node.get("asset", ""))
            if not asset.startswith("assets/"): fail("image.asset 必须位于 assets/")
        style = node.get("style")
        if style is not None and style not in ("title", "body", "caption", "number", "displayNumber"): fail("未知语义字号")
    visit(value["root"], 1)
    if value["scene"] == "settings" and has_config_inputs and not has_save: fail("设置表单包含草稿字段时必须提供 settings.save")
    if value["scene"] == "rear" and has_save: fail("settings.save 只能出现在设置场景")

def validate_effect_intent(effect, capabilities, field):
    if not isinstance(effect,dict) or not isinstance(effect.get("type"),str): fail(f"{field} effect 无效")
    kind=effect["type"]
    if kind=="action.invoke":
        if set(effect)!={"type","ref"} or "action.invoke" not in capabilities: fail(f"{field} action.invoke 必须声明该能力且只含 ref")
        ref=effect["ref"]
        if not isinstance(ref,dict) or set(ref)!={"id","version"} or not isinstance(ref.get("id"),str) or not re.fullmatch(r"[a-f0-9]{48}",ref["id"]) or isinstance(ref.get("version"),bool) or ref["version"]!=1: fail(f"{field} action.invoke ref 无效")
    elif kind=="schedule.visible.register":
        if "schedule.visible" not in capabilities or set(effect)-{"type","id","intervalMs","deadlineElapsedRealtimeMs"} or set(effect)<{"type","id"}: fail(f"{field} schedule register 必须声明 schedule.visible 并使用有界字段")
        if not isinstance(effect["id"],str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}",effect["id"]): fail(f"{field} schedule id 无效")
        interval=effect.get("intervalMs"); deadline=effect.get("deadlineElapsedRealtimeMs")
        if interval is None and deadline is None: fail(f"{field} schedule 至少需要 intervalMs 或 elapsedRealtime deadline")
        if interval is not None and (isinstance(interval,bool) or not isinstance(interval,int) or not 1000 <= interval <= 9007199254740991): fail(f"{field} schedule intervalMs 必须是至少 1000 的安全整数")
        if deadline is not None and (isinstance(deadline,bool) or not isinstance(deadline,int) or not 0 <= deadline <= 9007199254740991): fail(f"{field} elapsedRealtime deadline 必须为非负安全整数")
    elif kind=="schedule.visible.cancel":
        if "schedule.visible" not in capabilities or set(effect)!={"type","id"} or not isinstance(effect["id"],str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}",effect["id"]): fail(f"{field} schedule cancel 无效或未声明能力")
    elif kind=="rear.open":
        if "rear.scene" not in capabilities or set(effect)-{"type","id","scene","region","keepVisible"} or not {"type","scene","region","keepVisible"}.issubset(effect): fail(f"{field} rear.open 字段无效或未声明 rear.scene")
        if effect["scene"]!="rear" or effect["region"]!="CAMERA_SAFE" or not isinstance(effect["keepVisible"],bool): fail(f"{field} rear.open 参数不受支持")
        if "id" in effect and (not isinstance(effect["id"],str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}",effect["id"])): fail(f"{field} rear.open id 无效")
        if effect["keepVisible"] and "rear.scene.keep_visible" not in capabilities: fail(f"{field} rear.open keepVisible 需要声明 rear.scene.keep_visible")
    else: fail(f"{field} effect 类型未支持：{kind}")

def validate_manifest(m, entries=None):
    expected = {"format", "formatVersion", "id", "name", "version", "versionCode", "author", "platform", "minimumAndroidApi", "capabilities", "permissions", "scenes", "runtime", "data", "payloads"}
    if not isinstance(m, dict) or set(m) != expected: fail("manifest 字段缺失或包含未知字段")
    if m["format"] != FORMAT or isinstance(m["formatVersion"], bool) or m["formatVersion"] != 1: fail("仅支持 remrss.mod formatVersion 1")
    if not MOD_ID.fullmatch(str(m["id"])): fail("id 格式无效")
    if not isinstance(m["name"], str) or not 1 <= len(m["name"]) <= 80: fail("name 长度应为 1..80")
    if not SEMVER.fullmatch(str(m["version"])) or isinstance(m["versionCode"], bool) or not isinstance(m["versionCode"], int) or m["versionCode"] < 1: fail("版本信息无效")
    a = m["author"]
    if not isinstance(a, dict) or set(a) != {"name", "keyFingerprint"} or not 1 <= len(str(a["name"])) <= 80 or not re.fullmatch(r"[0-9a-f]{64}", str(a["keyFingerprint"])): fail("author 信息或 keyFingerprint 无效")
    p = m["platform"]
    if not isinstance(p, dict) or set(p) != {"api"} or isinstance(p["api"], bool) or not isinstance(p["api"], int) or p["api"] < 1: fail("platform.api 无效")
    if isinstance(m["minimumAndroidApi"], bool) or not isinstance(m["minimumAndroidApi"], int) or not 24 <= m["minimumAndroidApi"] <= 1000: fail("minimumAndroidApi 无效")
    if isinstance(m["platform"].get("api"), bool) or not isinstance(m["platform"].get("api"), int) or m["platform"]["api"] != 1: fail("当前仅支持 platform.api 1")
    for field in ("capabilities", "permissions"):
        obj = m[field]
        if not isinstance(obj, dict) or set(obj) != {"required", "optional"}: fail(f"{field} 必须声明 required/optional")
        for kind in ("required", "optional"):
            vals = obj[kind]
            if not isinstance(vals, list): fail(f"{field}.{kind} 必须为数组")
            if field == "capabilities":
                seen=set()
                for cap in vals:
                    if not isinstance(cap, dict) or set(cap) != {"name", "minimumVersion"} or not isinstance(cap["name"],str) or not TOKEN.fullmatch(cap["name"]): fail(f"{field}.{kind} 能力项无效")
                    version=cap["minimumVersion"]
                    if isinstance(version,bool) or not isinstance(version,int) or version<1: fail("minimumVersion 必须为正整数")
                    supported=CAPABILITY_NAMES.get(cap["name"])
                    if supported is None or version>supported: fail(f"宿主不认识能力或不支持请求版本：{cap['name']}@{version}")
                    if cap["name"] in seen: fail(f"{field}.{kind} 能力重复：{cap['name']}")
                    seen.add(cap["name"])
            else:
                if any(not isinstance(v, str) or not TOKEN.fullmatch(v) for v in vals) or len(set(vals)) != len(vals): fail(f"{field}.{kind} 无效")
        if field == "capabilities":
            required={x["name"] for x in obj["required"]}; optional={x["name"] for x in obj["optional"]}
        else:
            required=set(obj["required"]); optional=set(obj["optional"])
        if required & optional: fail(f"{field} required/optional 重复")
    scenes = m["scenes"]
    if not isinstance(scenes, dict) or set(scenes) != {"settings", "rear"}: fail("RC1 Mod 必须声明 settings 与 rear 场景")
    if "rear" not in scenes and any(c["name"] in ("rear.scene","rear.scene.keep_visible") for c in m["capabilities"]["required"]+m["capabilities"]["optional"]): fail("rear.scene 需要声明 rear 场景")
    for path in scenes.values():
        safe_path(path)
        if not path.endswith(".ui.json"): fail("scene path 必须是 .ui.json")
    rt = m["runtime"]
    if not isinstance(rt, dict) or rt.get("kind") not in ("none", "quickjs") or set(rt) - {"kind", "entry"}: fail("runtime.kind 仅 none 或 quickjs")
    if rt["kind"] == "quickjs" and m["minimumAndroidApi"] < 29: fail("quickjs Mod 的 minimumAndroidApi 至少为 29")
    if rt["kind"] == "none" and "entry" in rt: fail("无脚本 Mod 不得声明脚本入口")
    if rt["kind"] == "quickjs" and rt.get("entry") != "main.js": fail("quickjs Mod entry 必须为 main.js")
    data = m["data"]
    if not isinstance(data, dict) or set(data) != {"stateSchema", "configSchema", "dataVersion"}: fail("data 定义无效")
    if isinstance(data["dataVersion"], bool) or not isinstance(data["dataVersion"], int) or data["dataVersion"] < 1: fail("dataVersion 无效")
    if not isinstance(data["stateSchema"], dict) or data["stateSchema"].get("type") != "object" or not isinstance(data["configSchema"], dict) or data["configSchema"].get("type") != "object": fail("stateSchema/configSchema 须以 object 为根")
    validate_schema_definition(data["stateSchema"], "stateSchema")
    validate_schema_definition(data["configSchema"], "configSchema")
    if not isinstance(m["payloads"], list) or not 2 <= len(m["payloads"]) <= 62: fail("payloads 数量应为 2..62")
    names = []
    for item in m["payloads"]:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}: fail("payload entry 字段无效")
        path = safe_path(item["path"])
        if path in names: fail(f"payload 重复：{path}")
        names.append(path)
        if isinstance(item["bytes"], bool) or not isinstance(item["bytes"], int) or item["bytes"] < 0 or not re.fullmatch(r"[0-9a-f]{64}", str(item["sha256"])): fail(f"payload 元数据无效：{path}")
    if any(p not in names for p in scenes.values()): fail("settings/rear UI 必须在 payloads 中")
    if rt["kind"] == "quickjs" and "main.js" not in names: fail("quickjs 包必须包含 main.js")
    if rt["kind"] == "none" and "main.js" in names: fail("无脚本 Mod 包不得含 main.js")
    if entries is not None:
        if set(entries) != set(names) | {"manifest.json", "manifest.sig", "author.pub"}: fail("ZIP 内存在未声明或缺失条目")
        if len(entries) > MAX_FILES: fail("ZIP 文件数超过 64")
        total = 0
        for item in m["payloads"]:
            b = entries[item["path"]]
            total += len(b)
            if len(b) != item["bytes"] or hashlib.sha256(b).hexdigest() != item["sha256"]: fail(f"载荷完整性校验失败：{item['path']}")
        if total + len(entries["manifest.json"]) + len(entries["manifest.sig"]) + len(entries["author.pub"]) > MAX_EXPANDED: fail("ZIP 展开内容超过 8 MiB")
        if len(entries["manifest.json"]) > MAX_UI: fail("manifest 超过 64 KiB")
        for path in scenes.values():
            if len(entries[path]) > MAX_UI: fail(f"UI 声明超过 64 KiB：{path}")
        if rt["kind"] == "quickjs" and len(entries["main.js"]) > MAX_JS: fail("main.js 超过 512 KiB")
        requested_caps={x["name"] for x in m["capabilities"]["required"]+m["capabilities"]["optional"]}
        for path in scenes.values():
            try: ui = strict_json(entries[path].decode("utf-8"))
            except Exception as e: fail(f"{path}: UI JSON 无效：{e}")
            validate_ui(ui, data["stateSchema"], data["configSchema"], requested_caps, rt["kind"])
            def check_assets(node):
                if node.get("type") == "ui.image" and node.get("asset") not in names: fail(f"UI 引用未列入载荷的图片：{node.get('asset')}")
                for child in node.get("children", []): check_assets(child)
            check_assets(ui["root"])
        decoded_asset_bytes=0
        for path, b in entries.items():
            if path.startswith("assets/"):
                if Path(path).suffix.lower() not in (".png", ".webp"): fail(f"资源只允许 PNG/WebP：{path}")
                width,height=image_dimensions(b,path)
                if not 1<=width<=1024 or not 1<=height<=1024: fail(f"图片单边必须在 1..1024px：{path}")
                decoded_asset_bytes += width*height*4
        if decoded_asset_bytes>8*1024*1024: fail("本地图片解码缓存预算超过 8 MiB")

def openssl(*args, input_bytes=None) -> bytes:
    exe = shutil.which("openssl")
    if not exe: fail("需要 OpenSSL 命令行工具签名/验证；安装 OpenSSL 后重试")
    p = subprocess.run([exe, *map(str,args)], input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode: fail("OpenSSL 操作失败：" + p.stderr.decode("utf-8", "replace").strip())
    return p.stdout

def pub_der_from_private(key: Path) -> bytes:
    return openssl("pkey", "-in", key, "-pubout", "-outform", "DER")

def pub_pem_from_private(key: Path) -> bytes:
    return openssl("pkey", "-in", key, "-pubout")

def require_rsa_2048(pem: bytes, path: Path):
    with tempfile.TemporaryDirectory(prefix="remrss-mod-key-") as td:
        keyfile=Path(td)/"key.pem"; keyfile.write_bytes(pem)
        details=openssl("pkey","-pubin","-in",keyfile,"-text","-noout").decode("utf-8","replace")
    match=re.search(r"Public-Key:\s*\((\d+) bit\)",details)
    if not match or not 2048<=int(match.group(1))<=8192: fail(f"作者 RSA 公钥须在 2048..8192 位：{path}")
    return int(match.group(1))

def image_dimensions(data: bytes, path: str):
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data)>=24 and data[12:16]==b"IHDR":
        return int.from_bytes(data[16:20],"big"),int.from_bytes(data[20:24],"big")
    if len(data)>=30 and data[:4]==b"RIFF" and data[8:12]==b"WEBP":
        kind=data[12:16]
        if kind==b"VP8X":
            if data[20]&0x02: fail(f"不支持动画 WebP：{path}")
            return 1+int.from_bytes(data[24:27],"little"),1+int.from_bytes(data[27:30],"little")
        if kind==b"VP8L" and data[20]==0x2f:
            b1,b2,b3,b4=data[21:25]
            return 1+(((b2&0x3f)<<8)|b1),1+(((b4&0x0f)<<10)|(b3<<2)|((b2&0xc0)>>6))
        if kind==b"VP8 " and data[23:26]==b"\x9d\x01\x2a":
            return int.from_bytes(data[26:28],"little")&0x3fff,int.from_bytes(data[28:30],"little")&0x3fff
    fail(f"本地图片必须是有效静态 PNG/WebP：{path}")

def verify_zip(path: Path):
    if path.stat().st_size > MAX_ZIP: fail("ZIP 压缩体积超过 2 MiB")
    entries = {}
    try:
        with zipfile.ZipFile(path) as z:
            infos = z.infolist()
            if len(infos) > MAX_FILES: fail("ZIP 文件数超过 64")
            total = 0
            for info in infos:
                if info.is_dir(): fail("ZIP 不允许显式目录条目")
                name = safe_path(info.filename, allow_metadata=True)
                mode = info.external_attr >> 16
                if mode and (mode & 0o170000) == 0o120000: fail(f"ZIP 不允许符号链接：{name}")
                if name in entries: fail(f"ZIP 重复条目：{name}")
                total += info.file_size
                if info.file_size < 0 or total > MAX_EXPANDED: fail("ZIP 展开体积超过 8 MiB")
                content = z.read(info)
                if len(content) != info.file_size: fail(f"ZIP 条目展开长度不符：{name}")
                entries[name] = content
    except (zipfile.BadZipFile, RuntimeError, OSError) as e: fail(f"不是有效 ZIP：{e}")
    if not {"manifest.json", "manifest.sig", "author.pub"}.issubset(entries): fail("缺少 manifest.json、manifest.sig 或 author.pub")
    try: manifest = strict_json(entries["manifest.json"].decode("utf-8"))
    except Exception as e: fail(f"manifest.json 无效：{e}")
    validate_manifest(manifest, entries)
    try:
        pub_text = entries["author.pub"].decode("ascii")
        match = re.search(r"-----BEGIN PUBLIC KEY-----\s*(.*?)\s*-----END PUBLIC KEY-----", pub_text, re.S)
        if not match: fail("author.pub 必须为 SubjectPublicKeyInfo PEM")
        der = __import__("base64").b64decode(re.sub(r"\s+", "", match.group(1)), validate=True)
    except Exception as e: fail(f"author.pub 无效：{e}")
    bits=require_rsa_2048(entries["author.pub"], Path("author.pub"))
    if len(entries["manifest.sig"]) != bits//8: fail("RSA manifest.sig 长度与作者公钥不符")
    if hashlib.sha256(der).hexdigest() != manifest["author"]["keyFingerprint"]: fail("author.pub 指纹与签名 manifest 不一致")
    with tempfile.TemporaryDirectory(prefix="remrss-mod-verify-") as td:
        pub = Path(td)/"author.pub"; sig = Path(td)/"manifest.sig"; raw = Path(td)/"manifest.json"
        pub.write_bytes(entries["author.pub"]); sig.write_bytes(entries["manifest.sig"]); raw.write_bytes(entries["manifest.json"])
        openssl("dgst", "-sha256", "-verify", pub, "-signature", sig, raw)
    return manifest, entries

def init_project(target: Path, mod_id: str, template: str, author: str):
    if not MOD_ID.fullmatch(mod_id): fail("Mod id 格式无效")
    source = Path(__file__).parent / "templates" / template
    if not source.is_dir(): fail(f"未知模板：{template}")
    if target.exists() and any(target.iterdir()): fail(f"目标目录非空：{target}")
    target.mkdir(parents=True, exist_ok=True)
    for item in source.iterdir():
        if item.is_file(): shutil.copyfile(item, target/item.name)
        elif item.is_dir(): shutil.copytree(item, target/item.name)
    is_logic = template == "counter"
    required_capabilities = [{"name":"rear.scene", "minimumVersion":1}]
    optional_capabilities = [{"name":"rear.scene.keep_visible", "minimumVersion":1}]
    if template == "info":
        required_capabilities.append({"name":"data.battery", "minimumVersion":1})
    state_schema = ({"type":"object","properties":{
                        "count":{"type":"integer","minimum":-1000000000,"maximum":1000000000},
                        "stepUsed":{"type":"integer","minimum":1,"maximum":10},
                        "previousStep":{"type":"integer","minimum":1,"maximum":10}},
                     "required":["count","stepUsed","previousStep"],"additionalProperties":False}
                    if is_logic else {"type":"object","properties":{},"additionalProperties":False})
    config_schema = ({"type":"object","properties":{"step":{"type":"integer","minimum":1,"maximum":10,"default":1}},
                      "required":["step"],"additionalProperties":False}
                     if is_logic else {"type":"object","properties":{},"additionalProperties":False})
    manifest = {
        "format": FORMAT, "formatVersion": FORMAT_VERSION, "id": mod_id,
        "name": mod_id.replace("-", " ").title(), "version": "0.1.0", "versionCode": 1,
        "author": {"name": author, "keyFingerprint": "0"*64}, "platform": {"api": 1},
        "minimumAndroidApi": 29 if is_logic else 24,
        "capabilities": {"required": required_capabilities, "optional": optional_capabilities},
        "permissions": {"required": [], "optional": []},
        "scenes": {"settings": "settings.ui.json", "rear": "rear.ui.json"},
        "runtime": ({"kind":"quickjs", "entry":"main.js"} if is_logic else {"kind":"none"}),
        "data": {"stateSchema":state_schema,"configSchema":config_schema,"dataVersion":1},
        "payloads": []
    }
    (target/"manifest.json").write_bytes(canonical_json(manifest))

def project_files(root: Path, manifest):
    result=[]
    declared=set(manifest.get("scenes", {}).values())
    for path in declared: safe_path(path)
    if manifest.get("runtime", {}).get("kind") == "quickjs": declared.add("main.js")
    for p in sorted(root.rglob("*")):
        if not p.is_file(): continue
        rel=p.relative_to(root).as_posix()
        if rel in ("manifest.json", "manifest.sig", "author.pub", "README.md") or rel.startswith((".git/", "node_modules/", ".remrss/", "tests/")): continue
        if rel not in declared and not rel.startswith(("locales/", "assets/")):
            fail(f"不允许打包未声明的可执行/项目文件：{rel}")
        safe_path(rel)
        result.append((rel,p.read_bytes()))
    if len(result) + 3 > MAX_FILES: fail("Mod 文件数超过 64")
    if sum(map(lambda x: len(x[1]), result)) > MAX_EXPANDED: fail("Mod 内容超过 8 MiB")
    return result

def pack_project(root: Path, key: Path, output: Path):
    root=root.resolve(); manifest=read_json(root/"manifest.json"); files=project_files(root, manifest)
    by={n:b for n,b in files}
    # Derive public identity from the author's private key; private key never enters the package.
    pub_pem=pub_pem_from_private(key); der=pub_der_from_private(key)
    require_rsa_2048(pub_pem,key)
    manifest["author"]["keyFingerprint"]=hashlib.sha256(der).hexdigest()
    manifest["payloads"]=[{"path":n,"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()} for n,b in sorted(files)]
    raw=canonical_json(manifest)
    # Validate same source types and limits before signing.
    candidate={**by,"manifest.json":raw,"manifest.sig":b"placeholder","author.pub":pub_pem}
    validate_manifest(manifest,candidate)
    with tempfile.TemporaryDirectory(prefix="remrss-mod-sign-") as td:
        data=Path(td)/"manifest.json"; sig=Path(td)/"manifest.sig"; data.write_bytes(raw)
        openssl("dgst","-sha256","-sign",key,"-out",sig,data)
        signature=sig.read_bytes()
    output.parent.mkdir(parents=True,exist_ok=True)
    tmp=output.with_name(output.name+".tmp")
    with zipfile.ZipFile(tmp,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        z.writestr("manifest.json",raw)
        z.writestr("manifest.sig",signature)
        z.writestr("author.pub",pub_pem)
        for name,data in sorted(files): z.writestr(name,data)
    if tmp.stat().st_size > MAX_ZIP:
        tmp.unlink(missing_ok=True); fail("签名 ZIP 超过 2 MiB")
    tmp.replace(output)
    verify_zip(output)

def check_project(root: Path):
    manifest=read_json(root/"manifest.json"); files=project_files(root, manifest); by=dict(files)
    if not set(manifest.get("scenes",{}).values()).issubset(by): fail("缺少 settings/rear UI 文件")
    m=dict(manifest); m["payloads"]=[{"path":n,"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()} for n,b in sorted(files)]; m["author"]["keyFingerprint"]="0"*64
    validate_manifest(m,{**by,"manifest.json":canonical_json(m),"manifest.sig":b"", "author.pub":b""})
    return manifest

_NO_DEFAULT = object()

def schema_defaults(schema):
    if "default" in schema:
        return json.loads(json.dumps(schema["default"]))
    if schema.get("type") != "object":
        return _NO_DEFAULT
    return {key: value for key, child in schema.get("properties", {}).items()
            if (value := schema_defaults(child)) is not _NO_DEFAULT}

def merge_test_config(base, override):
    merged = json.loads(json.dumps(base))
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = merge_test_config(merged[key], value)
        else:
            merged[key] = value
    return merged

def run_tests(root: Path):
    js=root/"main.js"
    if not js.exists():
        check_project(root); print("PASS: 无脚本包声明/场景结构有效（未创建 JS runtime）"); return
    node=shutil.which("node")
    if not node: fail("main.js 业务测试需要 Node.js 18+；这只用于作者侧测试，不是 Android runtime")
    # Test the exact single-file worker ABI in a Node vm with no host bridge.
    cases_path=root/"tests"/"cases.json"
    migrations_path=root/"tests"/"migrations.json"
    config_changes_path=root/"tests"/"config-changes.json"
    try:
        manifest=check_project(root)
        capabilities={item["name"] for item in manifest["capabilities"]["required"]+manifest["capabilities"]["optional"]}
        config_schema=manifest["data"]["configSchema"]
        state_schema=manifest["data"]["stateSchema"]
        cases=strict_json(cases_path.read_text(encoding="utf-8")) if cases_path.exists() else []
        migrations=strict_json(migrations_path.read_text(encoding="utf-8")) if migrations_path.exists() else []
        config_changes=strict_json(config_changes_path.read_text(encoding="utf-8")) if config_changes_path.exists() else []
        if (not isinstance(cases,list) or len(cases)>128 or not isinstance(migrations,list) or len(migrations)>32
                or not isinstance(config_changes,list) or len(config_changes)>64): fail("测试用例必须是有界数组")
        defaults=schema_defaults(config_schema)
        if not isinstance(defaults,dict): fail("configSchema 须生成 object 默认配置")
        prepared=[]
        for index,test in enumerate(cases):
            field=f"tests.cases[{index}]"
            if not isinstance(test,dict) or set(test)-{"name","initial","events","expected","config"}: fail(f"{field} 字段无效")
            if not isinstance(test.get("name"),str) or not test["name"]: fail(f"{field}.name 必须为非空文字")
            if not isinstance(test.get("expected"),dict): fail(f"{field}.expected 必须为 state object")
            if "initial" in test and not isinstance(test["initial"],dict): fail(f"{field}.initial 必须为 state object")
            events=test.get("events",[])
            if not isinstance(events,list) or len(events)>128 or any(not isinstance(event,dict) for event in events): fail(f"{field}.events 必须为有界 event object 数组")
            if any(event.get("type") == "host.configChanged" for event in events): fail(f"{field}.events 不得伪造 host.configChanged；请用 tests/config-changes.json")
            if "initial" in test: validate_schema(state_schema,test["initial"],field+".initial")
            validate_schema(state_schema,test["expected"],field+".expected")
            override=test.get("config",{})
            if not isinstance(override,dict): fail(f"{field}.config 必须为 object")
            config=merge_test_config(defaults,override)
            validate_schema(config_schema,config,field+".config")
            prepared.append({**test,"config":config})
        cases=prepared
        for index,test in enumerate(migrations):
            field=f"tests.migrations[{index}]"
            if not isinstance(test,dict) or set(test)-{"name","state","config","fromVersion","toVersion","expected"}: fail(f"{field} 字段无效")
            if "state" not in test: fail(f"{field}.state 必须显式提供 object 或 null；缺失不等于 null")
            if not isinstance(test.get("name"),str) or not test["name"] or not (test["state"] is None or isinstance(test["state"],dict)) or not isinstance(test.get("config"),dict): fail(f"{field} 必须提供 name、object/null state 与 object config")
            if not isinstance(test.get("expected"),dict) or set(test["expected"])!={"state","config"}: fail(f"{field}.expected 必须包含 state/config")
            if not (test["expected"]["state"] is None or isinstance(test["expected"]["state"],dict)) or not isinstance(test["expected"]["config"],dict): fail(f"{field}.expected 必须为 object/null state 与 object config")
            if test["state"] is not None and test["expected"]["state"] is None: fail(f"{field}.expected.state 不得为 null：已有 state 不能被迁移清空")
            if any(isinstance(test.get(key),bool) or not isinstance(test.get(key),int) or test[key]<1 for key in ("fromVersion","toVersion")): fail(f"{field} fromVersion/toVersion 必须为正整数")
            if test["expected"]["state"] is not None: validate_schema(state_schema,test["expected"]["state"],field+".expected.state")
            validate_schema(config_schema,test["expected"]["config"],field+".expected.config")
        for index,test in enumerate(config_changes):
            field=f"tests.config-changes[{index}]"
            allowed={"name","state","previousConfig","config","expected","expectError"}
            if not isinstance(test,dict) or set(test)-allowed: fail(f"{field} 字段无效")
            if not isinstance(test.get("name"),str) or not test["name"]: fail(f"{field}.name 必须为非空文字")
            if not isinstance(test.get("state"),dict): fail(f"{field}.state 必须是已初始化 state object")
            if not isinstance(test.get("previousConfig"),dict) or not isinstance(test.get("config"),dict): fail(f"{field} 必须提供 previousConfig 与新 config object")
            validate_schema(state_schema,test["state"],field+".state")
            validate_schema(config_schema,test["previousConfig"],field+".previousConfig")
            validate_schema(config_schema,test["config"],field+".config")
            if ("expected" in test) == ("expectError" in test): fail(f"{field} 必须且只能包含 expected 或 expectError")
            if "expected" in test:
                expected=test["expected"]
                if not isinstance(expected,dict) or not {"state","config"}.issubset(expected) or set(expected)-{"state","config","effects"} or not isinstance(expected["state"],dict) or not isinstance(expected["config"],dict): fail(f"{field}.expected 必须包含 object state/config，可选 effects")
                validate_schema(state_schema,expected["state"],field+".expected.state")
                validate_schema(config_schema,expected["config"],field+".expected.config")
                if expected["config"] != test["config"]: fail(f"{field}.expected.config 必须等于用户提交的新 config；reduce 不能重写 config")
                if "effects" in expected:
                    if not isinstance(expected["effects"],list) or len(expected["effects"])>8: fail(f"{field}.expected.effects 必须为最多 8 个 effect object；宿主授权与激活仍需单独验证")
                    for effect_index,effect in enumerate(expected["effects"]): validate_effect_intent(effect,capabilities,f"{field}.expected.effects[{effect_index}]")
            else:
                if not isinstance(test["expectError"],str) or not test["expectError"]: fail(f"{field}.expectError 必须为非空错误文本")
    except Exception as e: fail(f"测试用例 JSON 无效：{e}")
    harness='''const fs=require('node:fs'); const vm=require('node:vm');
const source=fs.readFileSync(process.argv[2],'utf8');
const cases=JSON.parse(fs.readFileSync(process.argv[3],'utf8')); const migrations=JSON.parse(fs.readFileSync(process.argv[4],'utf8'));
const configChanges=JSON.parse(fs.readFileSync(process.argv[5],'utf8'));
const context=vm.createContext({}); context.__env={clock:{wallTimeMs:1700000000000,elapsedRealtimeMs:90000,timezoneId:'UTC',bootId:null}}; vm.runInContext(source,context,{timeout:1000});
context.__cases=cases; context.__migrations=migrations; context.__configChanges=configChanges; context.__defaultConfig=JSON.parse(fs.readFileSync(process.argv[6],'utf8'));
vm.runInContext(`const api=globalThis.__remrss; const env=globalThis.__env; if(!api||typeof api.init!=='function'||typeof api.reduce!=='function') throw new Error('main.js must define globalThis.__remrss={init,reduce}');
if(globalThis.__cases.length===0){const state=api.init({config:globalThis.__defaultConfig,env});if(!state||typeof state!=='object'||Array.isArray(state))throw new Error('init must return object state');}
for(const test of globalThis.__cases){const config=JSON.parse(JSON.stringify(test.config));const initialized=api.init({config,env});if(!initialized||typeof initialized!=='object'||Array.isArray(initialized))throw new Error(test.name+': init must return object state');let current=JSON.parse(JSON.stringify(test.initial??initialized));for(const event of test.events??[]){const result=api.reduce({state:current,config,env,event});if(!result||!('state'in result))throw new Error(test.name+': reduce must return {state}');current=result.state;}if(JSON.stringify(current)!==JSON.stringify(test.expected))throw new Error(test.name+': expected '+JSON.stringify(test.expected)+', got '+JSON.stringify(current));}
for(const test of globalThis.__migrations){if(typeof api.migrate!=='function')throw new Error('dataVersion update requires migrate');const result=api.migrate({state:test.state,config:test.config,fromVersion:test.fromVersion,toVersion:test.toVersion,env});if(!result||Object.keys(result).sort().join(',')!=='config,state'||'effects'in result)throw new Error(test.name+': migrate must return only {state,config}; effects are forbidden');if(test.state!==null&&result.state===null)throw new Error(test.name+': migrate cannot discard an initialized state');if(JSON.stringify(result.state)!==JSON.stringify(test.expected.state)||JSON.stringify(result.config)!==JSON.stringify(test.expected.config))throw new Error(test.name+': migration result mismatch');}
for(const test of globalThis.__configChanges){const input={state:JSON.parse(JSON.stringify(test.state)),config:JSON.parse(JSON.stringify(test.config)),previousConfig:JSON.parse(JSON.stringify(test.previousConfig)),event:{type:'host.configChanged',payload:{},eventId:'sdk-config-change',revision:7,logicalSessionId:'sdk-test'},env};let result;let failure;try{result=api.reduce(input);}catch(error){failure=String(error&&error.message||error);}if(test.expectError){if(!failure||!failure.includes(test.expectError))throw new Error(test.name+': expected reducer error containing '+test.expectError+', got '+failure);continue;}if(failure)throw new Error(test.name+': reducer failed: '+failure);if(!result||typeof result!=='object'||!('state'in result)||Object.keys(result).some(key=>!['state','effects'].includes(key)))throw new Error(test.name+': reduce must return {state,effects?} without extra fields');if('effects'in result&&(!Array.isArray(result.effects)||result.effects.length>8||result.effects.some(effect=>!effect||typeof effect!=='object'||Array.isArray(effect))))throw new Error(test.name+': Effects must be a bounded array of objects');if(!result.state||typeof result.state!=='object'||Array.isArray(result.state))throw new Error(test.name+': state must be an object');const canonical=value=>JSON.stringify(value&&typeof value==='object'&&!Array.isArray(value)?Object.fromEntries(Object.keys(value).sort().map(key=>[key,JSON.parse(canonical(value[key]))])):Array.isArray(value)?value.map(item=>JSON.parse(canonical(item))):value);if(canonical(result.state)!==canonical(test.expected.state)||canonical(input.config)!==canonical(test.expected.config)||canonical(result.effects??[])!==canonical(test.expected.effects??[]))throw new Error(test.name+': config-change result mismatch');}`,context,{timeout:1000});
console.log(`PASS: init/reduce ABI, ${cases.length} reducer case(s), ${migrations.length} migration case(s), ${configChanges.length} config-change case(s) (Node author-side check only)`);
'''
    with tempfile.TemporaryDirectory(prefix="remrss-mod-test-") as td:
        source=js.read_text(encoding="utf-8")
        src=Path(td)/"main.js"; src.write_text(source,encoding="utf-8")
        tests=Path(td)/"cases.json"; tests.write_text(json.dumps(cases),encoding="utf-8")
        migration_tests=Path(td)/"migrations.json"; migration_tests.write_text(json.dumps(migrations),encoding="utf-8")
        config_change_tests=Path(td)/"config-changes.json"; config_change_tests.write_text(json.dumps(config_changes),encoding="utf-8")
        default_config=Path(td)/"default-config.json"; default_config.write_text(json.dumps(defaults),encoding="utf-8")
        test=Path(td)/"test.cjs"; test.write_text(harness,encoding="utf-8")
        try: p=subprocess.run([node,str(test),str(src),str(tests),str(migration_tests),str(config_change_tests),str(default_config)],text=True,capture_output=True,timeout=4)
        except subprocess.TimeoutExpired: fail("Node reducer 测试超时（4 秒）")
        if p.returncode: fail("Node author-side test failed: " + (p.stderr.strip() or p.stdout.strip()))
        print(p.stdout.strip())
    check_project(root)

def preview_project(root: Path):
    m=check_project(root)
    # This is a native renderer contract preview bundle, not an HTML lookalike.
    print(f"UI 与资源预检通过：{root.resolve()}（{m['id']}）")
    print("实际视觉预览：将签名 ZIP 导入 ReMRSS 后，打开该 Mod 卡片的“本机预览”；它复用宿主 Compose/Miuix renderer，不申请背屏租约，也不执行业务 reducer。")

def dev_reload_package(root: Path, key: Path, installed: Path, output: Path):
    installed_manifest, _ = verify_zip(installed)
    check_project(root)
    with tempfile.TemporaryDirectory(prefix="remrss-mod-reload-") as td:
        candidate = Path(td) / "candidate.zip"
        pack_project(root, key, candidate)
        candidate_manifest, _ = verify_zip(candidate)
        comparisons = (
            ("id", installed_manifest["id"], candidate_manifest["id"]),
            ("作者指纹", installed_manifest["author"]["keyFingerprint"], candidate_manifest["author"]["keyFingerprint"]),
            ("versionCode", installed_manifest["versionCode"], candidate_manifest["versionCode"]),
            ("dataVersion", installed_manifest["data"]["dataVersion"], candidate_manifest["data"]["dataVersion"]),
        )
        for label, previous, current in comparisons:
            if previous != current:
                fail(f"Debug reload 的 {label} 必须与当前安装相同（当前 {previous}，候选 {current}）；正常升级请递增 versionCode 并走 Mod 管理导入/迁移")
        output.parent.mkdir(parents=True, exist_ok=True)
        tmp = output.with_name(output.name + ".tmp")
        shutil.copyfile(candidate, tmp); tmp.replace(output)
    print(f"已生成同作者 Debug reload 包：{output.resolve()}")
    print("请在 Debug 构建的“Mod 开发重载”入口选择此 ZIP；此操作不走正常升级或数据迁移。")

def main():
    parser=argparse.ArgumentParser(prog="remrss",description="ReMRSS ZIP Mod author tools")
    sub=parser.add_subparsers(dest="cmd",required=True)
    c=sub.add_parser("create"); c.add_argument("id"); c.add_argument("--template",choices=["blank","info","counter"],default="blank"); c.add_argument("--author",default="Mod author"); c.add_argument("--out",type=Path)
    chk=sub.add_parser("check"); chk.add_argument("target",type=Path)
    tst=sub.add_parser("test"); tst.add_argument("project",type=Path)
    prv=sub.add_parser("preview"); prv.add_argument("project",type=Path)
    pack=sub.add_parser("pack"); pack.add_argument("project",type=Path); pack.add_argument("--key",required=True,type=Path); pack.add_argument("--out",type=Path)
    dev=sub.add_parser("dev-reload",help="生成同作者 Debug 重载 ZIP（需提供当前已签名 ZIP）")
    dev.add_argument("project",type=Path); dev.add_argument("--key",required=True,type=Path); dev.add_argument("--installed",required=True,type=Path); dev.add_argument("--out",type=Path)
    args=parser.parse_args()
    try:
        if args.cmd=="create":
            out=args.out or Path(args.id); init_project(out,args.id,args.template,args.author); print(f"Created {out.resolve()} ({args.template})")
        elif args.cmd=="check":
            if args.target.is_dir(): check_project(args.target); print("PASS: source declarations and local resources are structurally valid")
            else:
                m,_=verify_zip(args.target); print(f"PASS: signature, author fingerprint and all {len(m['payloads'])} payloads verified ({m['id']} {m['version']})")
        elif args.cmd=="test": run_tests(args.project)
        elif args.cmd=="preview": preview_project(args.project)
        elif args.cmd=="pack":
            if not args.key.is_file(): fail(f"找不到作者私钥：{args.key}")
            output=args.out or args.project.with_suffix(".zip"); pack_project(args.project,args.key,output); print(f"Packed and verified {output.resolve()}")
        elif args.cmd=="dev-reload":
            if not args.key.is_file(): fail(f"找不到作者私钥：{args.key}")
            if not args.installed.is_file(): fail(f"找不到当前已签名 ZIP：{args.installed}")
            output=args.out or args.project.with_name(args.project.name+"-debug-reload.zip")
            dev_reload_package(args.project,args.key,args.installed,output)
        return 0
    except ModError as e:
        print(f"remrss: {e}",file=sys.stderr); return 2

if __name__=="__main__": sys.exit(main())

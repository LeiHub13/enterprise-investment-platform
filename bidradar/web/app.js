'use strict';
/* BidRadar — Vue3 + Element Plus 本地单页应用（无构建，CDN-free 本地 vendor） */
const { createApp, reactive, computed, ref, watch } = Vue;

const titles = { projects:'发现项目', intents:'采购意向', buyers:'关注采购人', topics:'关注主题', favorites:'收藏与跟进', runs:'搜索记录', workflow:'自动工作流', settings:'数据与设置' };
const STAGES = ['待核实','招标公告','采购意向','更正公告','中标结果','终止公告','合同公告'];
const FOLLOWUPS = ['待研究','准备参与','已参与','不感兴趣'];
const MANUAL_KINDS = { 招标公告:'opportunity', 更正公告:'correction', 中标结果:'result', 采购意向:'intent', 终止公告:'terminated', 合同公告:'contract' };
const NOTICE_LABELS = { opportunity:'招采机会', correction:'更正公告', result:'中标或成交结果', intent:'采购意向', terminated:'终止公告', contract:'合同公告', other:'类型待核实' };
const DEADLINE_LABELS = { open:'记录截止未到', today:'今日截止，时间待核实', unknown:'截止待核实', expired:'已过记录截止时间' };
const DEADLINE_RANKS = { open:0, today:1, unknown:2, expired:4 };
const CHANNEL_LABELS = { search:'DeepSeek 搜索', crawl:'站点采集', both:'搜索和采集' };

async function api(path, body) {
  const r = await fetch(path, body ? { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) } : {});
  const d = await r.json();
  if (!r.ok) throw new Error(d.error || '操作失败');
  return d;
}
function dateCN(v){ if(!v) return '待核实'; const d=new Date(v); return isNaN(d)?String(v):d.toLocaleDateString('zh-CN',{timeZone:'Asia/Shanghai'}) }
function datetimeCN(v){ if(!v) return ''; try{ return new Date(v).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }catch(e){ return String(v) } }
function noticeKind(p){ return MANUAL_KINDS[p.stage] || (NOTICE_LABELS[p.notice_kind] ? p.notice_kind : 'other'); }
function deadlineState(p, now, today){
  const recorded = p.deadline ? Date.parse(p.deadline + '+08:00') : Date.parse(p.official_deadline_at);
  if (Number.isFinite(recorded)) return recorded <= now ? 'expired' : 'open';
  if (!p.official_deadline) return 'unknown';
  if (p.official_deadline < today) return 'expired';
  return p.official_deadline === today ? 'today' : 'open';
}
function opportunityRank(p, deadline){
  if (p.is_notice===0 || p.demo) return 5;
  if (noticeKind(p)!=='opportunity') return 3;
  return DEADLINE_RANKS[deadline];
}

const rootOptions = {
  data(){
    return {
      data:{ topics:[],projects:[],matches:[],runs:[],workflows:[],workflow_runs:[],config:{} },
      view:'projects',
      clock:Date.now(),
      clockTimer:null,
      busy:false,
      scanningId:null,
      crawlingId:null,
      intentTopic:'', intentText:'', intentScanning:false,
      buyerDraft:'', buyerSaving:false, buyerId:null, buyerProjects:[], buyerNext:null,
      buyerLoading:false, buyerRequest:0,
      STAGES, FOLLOWUPS,
      icons: ElementPlusIconsVue,
      filters:{ text:'', topic:'', stage:'', deadline:'', origin:'all', sort:'opportunity', showNoise:false },
      topicModalVisible:false, topicSaving:false, topicForm:{ name:'',keywords:'',excludes:'',region:'' }, topicEditId:null,
      qccModalVisible:false, qccBusy:false, qccResult:'', qccForm:{ id:null, keyword:'', msg_type:'', page_index:1 },
      detailModalVisible:false, detailProject:null, detailSaving:false, detailForm:{ amount:'',deadline:'',stage:'',followup:'',notes:'' }, detailFetching:false,
      settingsForm:{ interval_hours:0, official_only:false },
      workflowModalVisible:false, workflowSaving:false, workflowEditId:null,
      workflowForm:{ name:'', enabled:true, freq:'daily', at_time:'09:00', interval_hours:24, topic_ids:[], auto_export:true, channels:'search' },
      runningWorkflowId:null,
      app: null
    };
  },
  computed:{
    providerLabel(){ return this.data.config.has_key ? '● DeepSeek 已配置' : '○ DeepSeek 待配置'; },
    currentTitle(){ return titles[this.view] || ''; },
    buyersAvailable(){ return Array.isArray(this.data.buyers); },
    watchedBuyers(){ return (this.data.buyers || []).filter(b=>b.followed); },
    selectedBuyer(){ return this.watchedBuyers.find(b=>b.id===this.buyerId); },
    projectTopics(){
      const names = new Map(this.data.topics.map(t=>[t.id,t.name]));
      const topics = new Map();
      for (const m of this.data.matches){
        const name = names.get(m.topic_id);
        if (!name) continue;
        if (!topics.has(m.project_id)) topics.set(m.project_id, []);
        topics.get(m.project_id).push({ id:m.topic_id, name });
      }
      return topics;
    },
    intentProjects(){
      const query = this.intentText.trim().toLowerCase();
      return this.data.projects.filter(p=>p.is_notice!==0 && !p.demo && noticeKind(p)==='intent'
        && (!this.intentTopic || this.projectTopics.get(p.id)?.some(t=>t.id===Number(this.intentTopic)))
        && (!query || (p.title+' '+(p.buyer_name||'')+' '+p.content).toLowerCase().includes(query)))
        .sort((a,b)=>(Date.parse(b.published)||0)-(Date.parse(a.published)||0) || b.id-a.id);
    },
    projectsList(){ return this.filterProjects(this.filters.showNoise); },
    hiddenNoise(){ return this.filterProjects(true).length - this.projectsList.length; },
    matchByProject(){
      const matches = new Map();
      for (const m of this.data.matches){
        if (this.filters.topic && m.topic_id!==Number(this.filters.topic)) continue;
        const current = matches.get(m.project_id);
        if (!current || m.score>current.score) matches.set(m.project_id,m);
      }
      return matches;
    },
    deadlineByProject(){
      const now = this.clock;
      const today = new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0,10);
      return new Map(this.data.projects.map(p=>[p.id, deadlineState(p, now, today)]));
    },
    stats(){
      const live = this.data.projects.filter(p=>!p.demo);
      return {
        projects: this.data.projects.filter(p=>p.is_notice!==0).length,
        noise: this.data.projects.filter(p=>p.is_notice===0).length,
        topics: this.data.topics.length,
        favs: this.data.projects.filter(p=>p.favorite).length,
        live: live.length,
        searched: live.filter(p=>p.origin==='search').length,
        crawled: live.filter(p=>p.origin==='crawl').length
      };
    },
    topicOptions(){ return [['','全部主题'], ...this.data.topics.map(t=>[t.id, t.name])]; },
    stageOptions(){ return [['','全部类型'], ...Object.entries(NOTICE_LABELS)]; },
    deadlineOptions(){ return [['','全部截止状态'], ...Object.entries(DEADLINE_LABELS)]; },
    // 来源渠道：搜索是 DeepSeek 索引，采集是直接读官方栏目页，两者索引源不同
    originOptions(){ return [['all','全部来源'],['search','DeepSeek 搜索'],['crawl','站点采集'],['qcc','企查查'],['demo','演示快照']]; },
    sortOptions(){ return [['opportunity','可参与机会优先'],['new','最近收录'],['published','公告日期 · 新→旧'],['published_asc','公告日期 · 旧→新'],['match','规则相关度']]; }
  },
  methods:{
    dateCN, datetimeCN,
    selectView(view){
      this.view = view;
      if (view==='buyers' && this.watchedBuyers.length){
        this.openBuyer(this.selectedBuyer?.id || this.watchedBuyers[0].id);
      }
    },
    topicLabels(p){ return (this.projectTopics.get(p.id)||[]).map(t=>t.name).join('、'); },
    async scanIntents(){
      if (this.busy || this.crawlingId || !this.data.config.has_key) return;
      const id = Number(this.intentTopic) || (this.data.topics.length===1 ? this.data.topics[0].id : null);
      if (!id){ this.toast('请选择要搜索采购意向的主题','warning'); return; }
      this.busy = true;
      this.intentScanning = true;
      try {
        const result = await api('/api/scan/intents',{id});
        await this.load();
        this.toast(`${result.status} · 命中 ${result.count} 条采购意向 · 新增 ${result.new} 条`,
          result.status==='成功' ? 'success' : 'warning');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.busy=false; this.intentScanning=false; }
    },
    async openBuyer(id){
      this.buyerId = id;
      this.buyerProjects = [];
      this.buyerNext = null;
      await this.loadBuyerProjects();
    },
    async loadBuyerProjects(more=false){
      const id = this.buyerId;
      if (!id || (more && (!this.buyerNext || this.buyerLoading))) return;
      const request = ++this.buyerRequest;
      const cursor = more ? '&before=' + this.buyerNext : '';
      this.buyerLoading = true;
      try {
        const result = await api('/api/buyers/projects?buyer=' + id + cursor);
        if (request!==this.buyerRequest || id!==this.buyerId) return;
        this.buyerProjects = more ? [...this.buyerProjects, ...result.projects] : result.projects;
        this.buyerNext = result.next_before;
      } catch(e){
        if (request===this.buyerRequest && id===this.buyerId) this.toast(e.message,'error');
      } finally {
        if (request===this.buyerRequest) this.buyerLoading = false;
      }
    },
    async followBuyer(name, projectId){
      name = String(name||'').trim();
      if (this.buyerSaving || !name) return;
      this.buyerSaving = true;
      try {
        const result = await api('/api/buyers',{name});
        if (!projectId) this.buyerDraft = '';
        await this.load();
        if (this.view==='buyers') await this.openBuyer(result.id);
        this.toast('已关注采购人');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.buyerSaving=false; }
    },
    async unfollowBuyer(buyer){
      if (this.buyerSaving) return;
      this.buyerSaving = true;
      try {
        await api('/api/buyers',{id:buyer.id,followed:false});
        await this.load();
        if (this.buyerId===buyer.id){
          this.buyerId = null;
          this.buyerProjects = [];
          this.buyerNext = null;
          if (this.watchedBuyers.length) await this.openBuyer(this.watchedBuyers[0].id);
        }
        this.toast('已取消关注');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.buyerSaving=false; }
    },
    filterProjects(includeNoise){
      let list = this.data.projects.filter(p => this.view!=='favorites' || p.favorite);
      // 疑似非公告（门户首页/栏目列表/聚合导航/新闻资讯）默认隐藏，可在列表头开关
      if (!includeNoise) list = list.filter(p => p.is_notice !== 0);
      if (this.filters.topic) list = list.filter(p=>this.matchByProject.has(p.id));
      if (this.filters.text){
        const q = this.filters.text.toLowerCase();
        list = list.filter(p=>(p.title+' '+p.content+' '+p.source).toLowerCase().includes(q));
      }
      if (this.filters.stage) list = list.filter(p=>noticeKind(p)===this.filters.stage);
      if (this.filters.deadline) list = list.filter(p=>this.deadlineByProject.get(p.id)===this.filters.deadline);
      if (this.filters.origin!=='all'){
        list = list.filter(p => this.filters.origin==='qcc' ? (p.qcc_available || p.origin==='qcc') :
          (p.origin || (p.demo ? 'demo' : 'search')) === this.filters.origin);
      }
      if (this.filters.sort==='opportunity'){
        list.sort((a,b)=>opportunityRank(a,this.deadlineByProject.get(a.id))-opportunityRank(b,this.deadlineByProject.get(b.id))
          || (this.matching(b)?.score||0)-(this.matching(a)?.score||0)
          || (Date.parse(b.published)||0)-(Date.parse(a.published)||0)
          || b.id-a.id);
      }
      if (this.filters.sort==='new') list.sort((a,b)=>String(b.first_seen_at||b.fetched_at).localeCompare(String(a.first_seen_at||a.fetched_at)) || b.id-a.id);
      if (this.filters.sort==='match') list.sort((a,b)=>(this.matching(b)?.score||0)-(this.matching(a)?.score||0));
      if (this.filters.sort==='published' || this.filters.sort==='published_asc'){
        const asc = this.filters.sort==='published_asc';
        list.sort((a,b)=>{
          const ta = Date.parse(a.published) || 0, tb = Date.parse(b.published) || 0;
          // 没有公告日期的条目始终沉底，不参与先后比较
          if ((!ta) !== (!tb)) return ta ? -1 : 1;
          if (ta !== tb) return asc ? ta - tb : tb - ta;
          return b.id - a.id;
        });
      }
      return list;
    },
    topicWords(t){ return String(t.keywords||'').split(/[,，;；\n]+/).filter(Boolean); },
    matching(p){ return this.matchByProject.get(p.id); },
    noticeLabel(p){ return NOTICE_LABELS[noticeKind(p)]; },
    deadlineLabel(p){ return DEADLINE_LABELS[this.deadlineByProject.get(p.id)] || DEADLINE_LABELS.unknown; },
    deadlineType(p){
      const state = this.deadlineByProject.get(p.id);
      return state==='open' ? 'success' : state==='today' ? 'warning' : 'info';
    },
    deadlineValue(p){
      if (p.deadline) return p.deadline.replace('T',' ') + '（人工）';
      if (p.official_deadline_at) return datetimeCN(p.official_deadline_at) + '（原文）';
      return p.official_deadline ? dateCN(p.official_deadline) + '（原文）' : '待核实';
    },
    matchCountFor(topicId){
      const ok = new Set(this.data.projects.filter(p=>p.is_notice!==0).map(p=>p.id));
      return new Set(this.data.matches.filter(m=>m.topic_id===topicId && ok.has(m.project_id)).map(m=>m.project_id)).size;
    },
    async load(){ this.data = await api('/api/state'); this.settingsForm = { interval_hours: this.data.config.interval_hours||0, official_only: !!this.data.config.official_only }; },
    toast(msg, type='success'){ ElementPlus.ElMessage({ message: msg, type }); },
    openQcc(topic){
      this.qccForm={ id:topic.id, keyword:topic.keywords.split(/[,，;；\n]/)[0].trim(), msg_type:'', page_index:1 };
      this.qccResult=''; this.qccModalVisible=true;
    },
    async searchQcc(){
      if (this.qccBusy || this.busy || this.crawlingId || !this.qccForm.keyword.trim()) return;
      this.qccBusy=true;
      try {
        const result=await api('/api/qcc/search',this.qccForm);
        await this.load();
        this.qccResult=`第 ${result.page} 页返回 ${result.returned} 条，匹配 ${result.count} 条，新增 ${result.new} 条；无原文链接 ${result.missing_url} 条，未匹配主题 ${result.unmatched} 条。`;
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.qccBusy=false; }
    },
    async scanTopic(id){
      if (this.busy) return;
      if (!this.data.config.has_key){ this.view='settings'; this.toast('请先配置 DEEPSEEK_API_KEY 并重启程序','warning'); return; }
      if (!id){
        if (this.filters.topic) id = Number(this.filters.topic);
        else if (this.data.topics.length===1) id = this.data.topics[0].id;
        else { this.view='topics'; this.toast('请选择一个主题进行搜索','warning'); return; }
      }
      this.busy = true; this.scanningId = id;
      this.toast('正在通过 DeepSeek 检索，每轮最多3次请求，请稍候…','info');
      try {
        const result = await api('/api/scan',{id});
        await this.load();
        this.toast(`${result.status}：匹配 ${result.count} 条线索${result.errors.length?'；详情见搜索记录':''}`, result.status==='成功'?'success':'warning');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.busy=false; this.scanningId=null; }
    },
    viewTopic(id){ this.filters.topic = String(id); this.view='projects'; },
    async crawlTopic(id){
      // 搜索和采集都会写项目库，同一时刻只让一个跑
      if (this.crawlingId || this.busy) return;
      this.crawlingId = id;
      this.toast('正在读取官方栏目页，每个源约需几秒…','info');
      try {
        const r = await api('/api/crawl',{id});
        await this.load();
        // 把每个源的产出说清楚：抓了几条、命中几条，比只报一个总数有用
        const detail = (r.sources||[]).filter(s=>s.listed).map(s=>`${s.name} ${s.listed} 条`).join('，');
        const tail = detail ? ` · 抓取：${detail}` : '';
        this.toast(`${r.status}：命中 ${r.count} 条，新增 ${r.new} 条${tail}`, r.status==='成功'?'success':'warning');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.crawlingId = null; }
    },
    openTopicForm(id){
      const t = this.data.topics.find(x=>x.id===id) || { name:'',keywords:'',excludes:'',region:'' };
      this.topicEditId = id ?? null;
      this.topicForm = { name:t.name||'', keywords:t.keywords||'', excludes:t.excludes||'', region:t.region||'' };
      this.topicModalVisible = true;
    },
    async saveTopic(){
      this.topicSaving = true;
      try {
        const payload = { ...this.topicForm };
        if (this.topicEditId) payload.id = this.topicEditId;
        await api('/api/topics', payload);
        this.topicModalVisible = false;
        await this.load();
        this.toast('主题已保存');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.topicSaving=false; }
    },
    async deleteTopic(id){
      try {
        await ElementPlus.ElMessageBox.confirm('删除这个主题？已收录的公告和收藏会保留。','删除确认',{ confirmButtonText:'删除', cancelButtonText:'取消', type:'warning' });
      } catch { return; }
      try {
        await api('/api/topics/delete',{id});
        if (Number(this.filters.topic)===id) this.filters.topic='';
        await this.load();
        this.toast('主题已删除');
      } catch(e){ this.toast(e.message,'error'); }
    },
    openDetail(project){
      const p = typeof project==='object' ? project : this.data.projects.find(x=>x.id===project);
      if (!p) return;
      this.detailProject = p;
      this.detailForm = { amount:p.amount||'', deadline:p.deadline||'', stage:p.stage||'待核实', followup:p.followup||'待研究', notes:p.notes||'', buyer_name:p.buyer_name||'' };
      this.detailModalVisible = true;
    },
    async saveDetail(){
      this.detailSaving = true;
      try {
        const { buyer_name, ...payload } = this.detailForm;
        if (this.buyersAvailable && buyer_name.trim()!==(this.detailProject.buyer_name||'')) payload.buyer_name = buyer_name.trim();
        await api('/api/projects',{ id:this.detailProject.id, ...payload });
        this.detailModalVisible=false;
        await this.load();
        if (this.view==='buyers') await this.loadBuyerProjects();
        this.toast('跟进信息已保存');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.detailSaving=false; }
    },
    async fetchPage(){
      this.detailFetching = true;
      try {
        const previous = this.detailProject;
        await api('/api/fetch',{id:previous.id});
        await this.load();
        let p = this.data.projects.find(x=>x.id===previous.id);
        if (!p && previous.buyer_id){
          const page = await api('/api/buyers/projects?buyer=' + previous.buyer_id + '&before=' + (previous.id+1));
          p = page.projects.find(x=>x.id===previous.id);
        }
        if (p && this.detailProject?.id===previous.id){
          this.detailProject = p;
          if (this.detailForm.buyer_name===(previous.buyer_name||'')) this.detailForm.buyer_name = p.buyer_name||'';
        }
        this.toast('正文已更新；金额和日期仍需核验','success');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.detailFetching=false; }
    },
    async toggleFavorite(p){
      try {
        await api('/api/projects',{ id:p.id, favorite: !p.favorite });
        await this.load();
      } catch(e){ this.toast(e.message,'error'); }
    },
    exportCSV(cmd){
      const all = !cmd || cmd === 'all';
      const t = all ? null : this.data.topics.find(x => String(x.id) === String(cmd));
      const d = new Date();
      const day = `${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}`;
      const safe = s => String(s).replace(/[\\/:*?"<>|\s]+/g,'_').slice(0,60);
      const a = document.createElement('a');
      a.href = all ? '/api/export' : '/api/export?topic=' + encodeURIComponent(cmd);
      a.download = all ? `标讯线索-全部-${day}.csv` : `标讯线索-${safe(t ? t.name : '主题'+cmd)}-${day}.csv`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      this.toast(all ? `正在导出全部 ${this.data.projects.length} 条线索` : `正在导出「${t ? t.name : cmd}」的线索`);
    },
    async loadDemo(){
      try { await api('/api/demo',{}); await this.load(); this.toast('已加载历史演示快照，不代表当前招标状态','info'); }
      catch(e){ this.toast(e.message,'error'); }
    },
    async deleteDemo(){
      try { await ElementPlus.ElMessageBox.confirm('删除所有演示公告及其收藏、备注？真实搜索数据不受影响。','删除演示数据',{ confirmButtonText:'删除', cancelButtonText:'取消', type:'warning' }); }
      catch { return; }
      try { await api('/api/demo/delete',{}); await this.load(); this.toast('演示数据已删除'); }
      catch(e){ this.toast(e.message,'error'); }
    },
    async saveSettings(){
      try {
        await api('/api/settings',{ interval_hours: Number(this.settingsForm.interval_hours), official_only: !!this.settingsForm.official_only });
        await this.load();
        this.toast('设置已保存');
      } catch(e){ this.toast(e.message,'error'); }
    },
    /* ---------- 自动工作流 ---------- */
    workflowTiming(wf){
      if (wf.freq==='interval') return `每 ${wf.interval_hours} 小时`;
      return `每天 ${wf.at_time}`;
    },
    workflowChannel(channel){ return CHANNEL_LABELS[channel] || CHANNEL_LABELS.search; },
    workflowTopics(wf){
      const ids = String(wf.topic_ids||'').split(',').filter(Boolean).map(Number);
      if (!ids.length) return '全部主题';
      const names = ids.map(id => (this.data.topics.find(t=>t.id===id)||{}).name).filter(Boolean);
      return names.length ? names.join('、') : '全部主题';
    },
    workflowLastRun(wf){
      const run = this.data.workflow_runs.find(r => r.workflow_id === wf.id);
      return run ? datetimeCN(run.started_at) : '尚未执行';
    },
    openWorkflowForm(id){
      const wf = this.data.workflows.find(x=>x.id===id);
      this.workflowEditId = wf ? wf.id : null;
      this.workflowForm = wf ? {
        name: wf.name||'',
        enabled: !!wf.enabled,
        freq: wf.freq||'daily',
        at_time: wf.at_time||'09:00',
        interval_hours: wf.interval_hours||24,
        topic_ids: String(wf.topic_ids||'').split(',').filter(Boolean).map(Number),
        auto_export: !!wf.auto_export,
        channels: wf.channels||'search'
      } : { name:'每日巡检', enabled:true, freq:'daily', at_time:'09:00', interval_hours:24, topic_ids:[], auto_export:true, channels:this.data.config.has_key?'both':'crawl' };
      this.workflowModalVisible = true;
    },
    async saveWorkflow(){
      this.workflowSaving = true;
      try {
        const payload = { ...this.workflowForm, topic_ids: this.workflowForm.topic_ids || [] };
        if (this.workflowEditId) payload.id = this.workflowEditId;
        await api('/api/workflows', payload);
        this.workflowModalVisible = false;
        await this.load();
        this.toast('工作流已保存，定时从下一个周期开始；想立刻跑可点「立即运行」');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.workflowSaving = false; }
    },
    async toggleWorkflow(wf){
      // 只提交开关状态：后端按字段合并，不会用页面缓存的旧值覆盖名称等配置
      try {
        await api('/api/workflows',{ id:wf.id, enabled: wf.enabled?0:1 });
        await this.load();
        this.toast(wf.enabled ? '已停用定时执行' : '已启用定时执行');
      } catch(e){ this.toast(e.message,'error'); }
    },
    async deleteWorkflow(id){
      try {
        await ElementPlus.ElMessageBox.confirm('删除这条工作流？已收录的线索和历史运行记录都会保留。','删除确认',{ confirmButtonText:'删除', cancelButtonText:'取消', type:'warning' });
      } catch { return; }
      try { await api('/api/workflows/delete',{id}); await this.load(); this.toast('工作流已删除'); }
      catch(e){ this.toast(e.message,'error'); }
    },
    async runWorkflowNow(id){
      if (this.runningWorkflowId) return;
      const wf = this.data.workflows.find(w=>w.id===id);
      if ((wf?.channels||'search')==='search' && !this.data.config.has_key){
        this.toast('仅搜索的工作流需要 DeepSeek API Key；也可以编辑工作流启用站点采集。','warning');
        return;
      }
      this.runningWorkflowId = id;
      this.toast('工作流已开始，正在按选定通道查找线索…','info');
      try {
        const r = await api('/api/workflows/run',{ id });
        await this.load();
        this.toast(`${r.status}：${r.topics_ok}/${r.topics_total} 个主题完成，新增 ${r.new} 条`, r.status==='成功'?'success':'warning');
      } catch(e){ this.toast(e.message,'error'); }
      finally { this.runningWorkflowId = null; }
    },
    parseRunList(value){
      try {
        const list = typeof value==='string' ? JSON.parse(value) : value;
        return Array.isArray(list) ? list : [];
      } catch(e){ return []; }
    },
    exportRun(row){
      if (!row.new_count){ this.toast('这一轮没有新增线索，无需导出','info'); return; }
      const day = String(row.started_at||'').slice(0,10).replace(/-/g,'');
      const a = document.createElement('a');
      a.href = '/api/export?run=' + row.id;
      a.download = `工作流新增-第${row.id}轮-${day}.csv`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      this.toast(`正在导出第 ${row.id} 轮新增的 ${row.new_count} 条线索`);
    },
    async refresh(){ try { await this.load(); this.toast('已刷新'); } catch(e){ this.toast(e.message,'error'); } }
  },
  created(){
    this.app = this;
    this.load().catch(err=>{
      ElementPlus.ElMessage({ message:'无法连接本地服务：'+err.message, type:'error' });
    });
  },
  mounted(){
    this.clockTimer = setInterval(()=>{ this.clock = Date.now(); }, 60000);
  },
  unmounted(){
    clearInterval(this.clockTimer);
  }
};

/* ===== Element Plus 模板（按视图分段拼接，避免单条过长） ===== */
const TPL = [];

TPL.push(`<div class="layout">
  <aside class="sidebar">
    <div class="brand"><span class="brand-icon">◉</span><div class="brand-text"><b>标讯雷达</b><small>BIDRADAR / WORKSPACE</small></div></div>
    <div class="nav-label">工作空间</div>
    <el-menu :default-active="view" class="side-menu" @select="selectView" background-color="transparent" text-color="#1f2328" active-text-color="#1f2328">
      <el-menu-item index="projects"><el-icon><grid /></el-icon><span>发现项目</span></el-menu-item>
      <el-menu-item index="intents"><el-icon><calendar /></el-icon><span>采购意向</span></el-menu-item>
      <el-menu-item v-if="buyersAvailable" index="buyers"><el-icon><office-building /></el-icon><span>关注采购人</span></el-menu-item>
      <el-menu-item index="topics"><el-icon><collection /></el-icon><span>关注主题</span></el-menu-item>
      <el-menu-item index="favorites"><el-icon><star /></el-icon><span>收藏与跟进</span></el-menu-item>
      <el-menu-item index="runs"><el-icon><refresh-right /></el-icon><span>搜索记录</span></el-menu-item>
      <el-menu-item index="workflow"><el-icon><alarm-clock /></el-icon><span>自动工作流</span></el-menu-item>
      <el-menu-item index="settings"><el-icon><setting /></el-icon><span>数据与设置</span></el-menu-item>
    </el-menu>
    <div class="sidebar-note"><i class="dot"></i> 本地私人工作空间<p>寻找相关项目，<br>让机会更清晰。</p><small>数据保存在本机 · v0.2</small></div>
  </aside>
  <main class="main">
    <header class="topbar">
      <div class="crumb">工作空间 <span>/</span> <b>{{ currentTitle }}</b></div>
      <div class="header-right"><el-tag :type="data.config.has_key ? 'success' : 'info'" size="small" effect="plain">{{ providerLabel }}</el-tag></div>
    </header>
    <section class="content">

    <!-- ============ 发现项目 / 收藏与跟进 ============ -->
    <template v-if="view==='projects' || view==='favorites'">
      <div class="page-head">
        <div class="page-title">
          <div class="eyebrow">INTELLIGENCE FOR YOUR NEXT OPPORTUNITY</div>
          <h1>{{ currentTitle }}</h1>
          <p class="muted">{{ view==='favorites' ? '把值得跟进的机会，放在同一个地方。' : '从你的关注主题出发，发现值得跟进的招标线索。' }}</p>
        </div>
        <div class="page-actions">
          <el-dropdown trigger="click" placement="bottom-end" @command="exportCSV">
            <el-button><el-icon><download /></el-icon>导出 CSV<el-icon class="el-icon--right"><arrow-down /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="all">全部线索（{{ data.projects.length }} 条）</el-dropdown-item>
                <el-dropdown-item v-for="(t,i) in data.topics" :key="t.id" :command="String(t.id)" :divided="i===0">{{ t.name }}（{{ matchCountFor(t.id) }} 条）</el-dropdown-item>
                <el-dropdown-item v-if="!data.topics.length" disabled>暂无关注主题</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button type="primary" :loading="busy" :disabled="!!crawlingId" @click="scanTopic(null)"><el-icon><search /></el-icon>{{ busy?'搜索进行中':(crawlingId?'采集进行中':'搜索当前主题') }}</el-button>
        </div>
      </div>

      <el-row :gutter="16" class="stats-row">
        <el-col :xs="12" :md="6"><el-card shadow="never" class="stat-card"><div class="stat-label">已收录公告</div><div class="stat-value">{{ stats.projects }}</div><div class="stat-sub">以原文网址去重<template v-if="stats.noise"> · 隐藏 {{ stats.noise }} 条非公告</template></div></el-card></el-col>
        <el-col :xs="12" :md="6"><el-card shadow="never" class="stat-card"><div class="stat-label">关注主题</div><div class="stat-value">{{ stats.topics }}</div><div class="stat-sub">自定义关键词与排除条件</div></el-card></el-col>
        <el-col :xs="12" :md="6"><el-card shadow="never" class="stat-card"><div class="stat-label">收藏项目</div><div class="stat-value">{{ stats.favs }}</div><div class="stat-sub">持续积累你的商机清单</div></el-card></el-col>
        <el-col :xs="12" :md="6"><el-card shadow="never" class="stat-card"><div class="stat-label">真实线索总量</div><div class="stat-value">{{ stats.live }}</div><div class="stat-sub">搜索 {{ stats.searched }} · 采集 {{ stats.crawled }}</div></el-card></el-col>
      </el-row>

      <el-alert v-if="!data.config.has_key" type="warning" :closable="false" show-icon title="搜索服务待配置" description="站点采集可直接使用。使用 DeepSeek 搜索前，请在「数据与设置」查看密钥配置方法。" class="mt-16"/>
      <el-alert v-else type="info" :closable="false" show-icon title="可参与机会优先" description="优先显示记录截止时间未到的招采机会。请以采购方最新公告为准。" class="mt-16"/>

      <el-card shadow="never" class="mt-16">
        <el-row :gutter="12" class="project-filters">
          <el-col :xs="24" :sm="8"><el-input v-model="filters.text" placeholder="搜索名称、采购内容或来源" clearable><template #prefix><el-icon><search /></el-icon></template></el-input></el-col>
          <el-col :xs="12" :sm="4"><el-select v-model="filters.topic" placeholder="全部主题"><el-option v-for="opt in topicOptions" :key="String(opt[0])" :label="opt[1]" :value="String(opt[0])"/></el-select></el-col>
          <el-col :xs="12" :sm="4"><el-select v-model="filters.stage" placeholder="全部类型"><el-option v-for="opt in stageOptions" :key="opt[0]" :label="opt[1]" :value="opt[0]"/></el-select></el-col>
          <el-col :xs="12" :sm="4"><el-select v-model="filters.deadline" placeholder="全部截止状态"><el-option v-for="opt in deadlineOptions" :key="opt[0]" :label="opt[1]" :value="opt[0]"/></el-select></el-col>
          <el-col :xs="12" :sm="4"><el-select v-model="filters.origin" placeholder="全部来源"><el-option v-for="opt in originOptions" :key="opt[0]" :label="opt[1]" :value="opt[0]"/></el-select></el-col>
        </el-row>
      </el-card>

      <div class="list-head mt-16">
        <span class="muted">{{ projectsList.length }} 条项目线索 · 点击标题查看原文摘录与跟进<template v-if="!filters.showNoise && hiddenNoise"> · 已隐藏 {{ hiddenNoise }} 条疑似非公告</template></span>
        <div class="sort-bar">
          <el-checkbox v-model="filters.showNoise" size="small" class="noise-toggle">显示疑似非公告</el-checkbox>
          <span class="tiny muted">排序</span>
          <el-select v-model="filters.sort" size="small" style="width:172px"><el-option v-for="opt in sortOptions" :key="opt[0]" :label="opt[1]" :value="opt[0]"/></el-select>
        </div>
      </div>

      <div class="cards mt-16" v-if="projectsList.length">
        <el-card v-for="p in projectsList" :key="p.id" shadow="hover" class="project-card" :body-style="{padding:'0'}">
          <div class="pc-top">
            <div class="pc-main">
              <div class="badges">
                <el-tag v-if="p.demo" type="warning" size="small" effect="plain">历史演示 · 2026-11-02快照</el-tag>
                <el-tag v-else-if="p.origin==='crawl'" type="primary" size="small" effect="plain">站点采集线索</el-tag>
                <el-tag v-else-if="p.origin==='qcc'" type="primary" size="small" effect="plain">企查查线索</el-tag>
                <el-tag v-else type="success" size="small" effect="plain">DeepSeek 搜索线索</el-tag>
                <el-tag v-if="p.is_notice===0" type="info" size="small" effect="plain" class="noise-tag">疑似非公告</el-tag>
                <el-tag v-else type="info" size="small" effect="plain">{{ noticeLabel(p) }}</el-tag>
                <el-tag v-if="p.is_notice!==0" :type="deadlineType(p)" size="small" effect="plain">{{ deadlineLabel(p) }}</el-tag>
              </div>
              <el-link type="primary" :underline="false" class="pc-title" @click="openDetail(p.id)">{{ p.title }}</el-link>
            </div>
            <el-button circle text @click="toggleFavorite(p)" :aria-label="p.favorite?'取消收藏':'收藏项目'" class="fav-btn"><el-icon v-if="p.favorite"><star-filled /></el-icon><el-icon v-else><star /></el-icon></el-button>
          </div>
          <p class="pc-excerpt">{{ p.content }}</p>
          <div class="pc-meta">
            <div><small>金额 / 预算</small><b>{{ p.amount || '待核实' }}</b></div>
            <div><small>来源网站</small><b>{{ p.source }}</b></div>
            <div><small>公告日期 / 未独立核验</small><b>{{ dateCN(p.published) }}</b></div>
            <div><small>截止时间</small><b>{{ deadlineValue(p) }}</b></div>
          </div>
          <div class="pc-bottom">
            <span class="reason">{{ matching(p) ? matching(p).reason : '尚无主题匹配 · 可在关注主题中配置关键词' }}</span>
            <div class="pc-actions"><el-tag size="small" effect="plain">{{ p.followup }}</el-tag><el-button text type="primary" @click="openDetail(p.id)">查看详情 <el-icon><arrow-right /></el-icon></el-button></div>
          </div>
        </el-card>
      </div>

      <el-empty v-else :description="data.projects.length ? '没有符合筛选条件的项目' : '从第一个关注主题开始'">
        <template #default>
          <p class="muted">{{ data.projects.length ? '试着调整关键词、主题、公告类型或截止状态。' : '创建主题后可采集站点公告，或配置 DeepSeek 密钥进行网页搜索。' }}</p>
        </template>
        <template #image><el-icon class="empty-icon"><collection /></el-icon></template>
        <template #extra>
          <el-button @click="openTopicForm(null)">＋ 创建主题</el-button>
          <el-button @click="loadDemo">加载历史演示公告</el-button>
        </template>
      </el-empty>
    </template>

    <template v-else-if="view==='intents'">
      <div class="page-head">
        <div class="page-title">
          <h1>{{ currentTitle }}</h1>
          <p class="muted">提前了解拟采购事项，收藏值得跟进的意向，等正式公告发布后再判断能否参与。</p>
        </div>
        <div class="page-actions">
          <el-link href="https://cgyx.ccgp.gov.cn/cgyx/pub/pubSearch" type="primary" target="_blank" rel="noopener noreferrer">查询官方意向库<el-icon class="el-icon--right"><Link /></el-icon></el-link>
        </div>
      </div>
      <el-card shadow="never">
        <div class="buyer-toolbar">
          <el-select v-model="intentTopic" aria-label="意向主题" class="intent-topic">
            <el-option v-for="opt in topicOptions" :key="String(opt[0])" :label="opt[1]" :value="String(opt[0])"/>
          </el-select>
          <el-input v-model="intentText" aria-label="筛选采购意向" placeholder="筛选已收录的意向、采购人或正文" clearable class="intent-search">
            <template #prefix><el-icon><search /></el-icon></template>
          </el-input>
          <el-button type="primary" :loading="intentScanning" :disabled="!data.config.has_key || !!crawlingId || (busy && !intentScanning)" @click="scanIntents">
            <el-icon><search /></el-icon>搜索采购意向
          </el-button>
        </div>
        <div class="form-hint">{{ data.config.has_key ? '选择主题后，可用 DeepSeek 在中国政府采购网意向库内搜索。' : '可先使用官方意向库。主题搜索需配置 DeepSeek 密钥。' }}</div>
        <div class="form-hint">主题的「爬取项目」及启用站点采集的工作流也会收录匹配的徐州政府采购意向。</div>
      </el-card>
      <div class="list-head mt-16"><span class="muted">{{ intentProjects.length }} 条已收录采购意向 · 按发布日期排序</span></div>
      <div v-if="intentProjects.length" class="cards mt-16">
        <el-card v-for="p in intentProjects" :key="p.id" shadow="never" class="buyer-notice">
          <div class="buyer-notice-row">
            <div class="buyer-notice-main">
              <el-button link type="primary" class="buyer-notice-title" @click="openDetail(p)">{{ p.title }}</el-button>
              <div class="buyer-notice-meta">
                <span>采购人 {{ p.buyer_name||'待核实' }}</span>
                <span>发布 {{ dateCN(p.published) }}</span>
                <span v-if="topicLabels(p)">主题 {{ topicLabels(p) }}</span>
              </div>
            </div>
            <div class="actions">
              <el-button size="small" :plain="!p.favorite" :type="p.favorite?'success':'default'" @click="toggleFavorite(p)">{{ p.favorite?'已收藏':'收藏意向' }}</el-button>
              <el-link type="primary" :href="p.url" target="_blank" rel="noopener noreferrer">原文</el-link>
            </div>
          </div>
        </el-card>
      </div>
      <el-empty v-else description="暂无符合条件的采购意向"/>
    </template>

    <template v-else-if="view==='buyers' && buyersAvailable">
      <div class="page-head">
        <div class="page-title">
          <h1>{{ currentTitle }}</h1>
          <p class="muted">保存重点采购单位，集中回看本地已收录并关联的意向、采购公告和结果。</p>
        </div>
      </div>
      <el-card shadow="never">
        <form class="buyer-toolbar" @submit.prevent="followBuyer(buyerDraft)">
          <el-input v-model="buyerDraft" maxlength="160" aria-label="采购人全称" placeholder="输入采购单位全称" clearable class="intent-search"/>
          <el-button native-type="submit" type="primary" :loading="buyerSaving" :disabled="!buyerDraft.trim()"><el-icon><plus /></el-icon>添加关注</el-button>
        </form>
        <div class="form-hint">关注用于整理已收录线索。可在公告详情核对并填写采购人，关联后即可在这里查看。</div>
      </el-card>
      <div v-if="watchedBuyers.length" class="buyer-grid mt-16">
        <el-card shadow="never" class="buyer-watch-list">
          <div v-for="buyer in watchedBuyers" :key="buyer.id" class="buyer-watch-row">
            <button type="button" :class="{active: buyer.id===buyerId}" :aria-pressed="buyer.id===buyerId" @click="openBuyer(buyer.id)">
              <b>{{ buyer.name }}</b><small>{{ buyer.project_count }} 条关联公告</small>
            </button>
          </div>
        </el-card>
        <el-card v-if="selectedBuyer" shadow="never" class="buyer-history">
          <div class="buyer-notice-row">
            <h2 class="buyer-history-title">{{ selectedBuyer.name }}</h2>
            <el-button size="small" :disabled="buyerSaving" @click="unfollowBuyer(selectedBuyer)">取消关注</el-button>
          </div>
          <div v-loading="buyerLoading" class="buyer-history-list">
            <div v-for="p in buyerProjects" :key="p.id" class="buyer-notice-row buyer-history-item">
              <div class="buyer-notice-main">
                <el-button link type="primary" class="buyer-notice-title" @click="openDetail(p)">{{ p.title }}</el-button>
                <div class="buyer-notice-meta">
                  <span>{{ noticeLabel(p) }}</span>
                  <span>发布 {{ dateCN(p.published) }}</span>
                  <span v-if="topicLabels(p)">主题 {{ topicLabels(p) }}</span>
                </div>
              </div>
              <el-link type="primary" :href="p.url" target="_blank" rel="noopener noreferrer">原文</el-link>
            </div>
            <el-empty v-if="!buyerLoading && !buyerProjects.length" description="还没有关联公告" :image-size="72"/>
          </div>
          <el-button v-if="buyerNext" size="small" class="mt-8" :loading="buyerLoading" @click="loadBuyerProjects(true)">加载更多</el-button>
        </el-card>
      </div>
      <el-empty v-else description="添加第一个关注采购人"/>
    </template>

    <!-- ============ 关注主题 ============ -->
    <template v-else-if="view==='topics'">
      <div class="page-head">
        <div class="page-title">
          <div class="eyebrow">INTELLIGENCE FOR YOUR NEXT OPPORTUNITY</div>
          <h1>{{ currentTitle }}</h1>
          <p class="muted">定义你关心的业务，让每次搜索更有方向。</p>
        </div>
        <div class="page-actions"><el-button type="primary" @click="openTopicForm(null)"><el-icon><plus /></el-icon>新建主题</el-button></div>
      </div>
      <el-alert type="info" :closable="false" show-icon title="搜索和采集" description="每轮最多 3 次 DeepSeek 搜索，优先搜索核心词，轮换补搜其他词或地区。站点采集直接读取官方栏目，地区命中用于提高相关度。两条通道可在发现项目页按来源筛选。" class="mt-16"/>
      <div class="topic-grid mt-16" v-if="data.topics.length">
        <el-card v-for="t in data.topics" :key="t.id" shadow="hover" class="topic-card clickable" :body-style="{padding:'20px'}" @click="viewTopic(t.id)">
          <div class="eyebrow topic-code">TOPIC / {{ String(t.id).padStart(2,'0') }}</div>
          <h2 class="topic-name">{{ t.name }}</h2>
          <p class="muted" style="font-size:13px">地区：{{ t.region||'不限' }} · {{ matchCountFor(t.id) }} 条匹配</p>
          <div class="tag-row mt-8">
            <el-tag v-for="w in topicWords(t)" :key="w" size="small" type="primary" effect="plain" style="margin:2px 4px 2px 0">{{ w }}</el-tag>
          </div>
          <p class="muted" style="font-size:12px;margin:8px 0 0">排除：{{ t.excludes||'未设置' }}</p>
          <div class="topic-actions" @click.stop>
            <el-button type="primary" size="small" @click="viewTopic(t.id)">查看线索</el-button>
            <el-button size="small" :loading="scanningId===t.id" :disabled="(busy||!!crawlingId) && scanningId!==t.id" @click="scanTopic(t.id)">搜索项目</el-button>
            <el-button size="small" :loading="crawlingId===t.id" :disabled="(busy||!!crawlingId) && crawlingId!==t.id" @click="crawlTopic(t.id)">爬取项目</el-button>
            <el-button size="small" :disabled="busy||!!crawlingId||qccBusy" @click="openQcc(t)">企查查查询</el-button>
            <span class="topic-actions-more">
              <el-tooltip content="编辑主题" placement="top" :show-after="400"><el-button size="small" text circle @click="openTopicForm(t.id)"><el-icon><Edit /></el-icon></el-button></el-tooltip>
              <el-tooltip content="删除主题" placement="top" :show-after="400"><el-button size="small" text circle type="danger" @click="deleteTopic(t.id)"><el-icon><Delete /></el-icon></el-button></el-tooltip>
            </span>
          </div>
        </el-card>
      </div>
      <el-empty v-else description="你想找到什么项目？">
        <template #default><p class="muted">例如：OPC社区运营、AI知识库平台建设、企业数字化培训。</p></template>
        <template #extra><el-button type="primary" @click="openTopicForm(null)">创建第一个主题</el-button></template>
      </el-empty>
    </template>

    <!-- ============ 搜索记录 ============ -->
    <template v-else-if="view==='runs'">
      <div class="page-head">
        <div class="page-title">
          <div class="eyebrow">SEARCH HISTORY</div>
          <h1>{{ currentTitle }}</h1>
          <p class="muted">记录每一次检索的结果，失败与部分成功也会如实保留。</p>
        </div>
        <div class="page-actions"><el-button @click="refresh"><el-icon><refresh /></el-icon>刷新记录</el-button></div>
      </div>
      <el-table :data="data.runs" style="width:100%" empty-text="暂无搜索记录。创建主题后运行第一次搜索或采集。">
        <el-table-column prop="topic_name" label="主题"/>
        <el-table-column label="通道" width="110"><template #default="{row}"><el-tag size="small" :type="row.kind==='crawl'?'primary':'success'" effect="plain">{{ row.kind==='crawl' ? '站点采集' : row.kind==='intent' ? '采购意向' : 'DeepSeek' }}</el-tag></template></el-table-column>
        <el-table-column label="开始时间（北京时间）"><template #default="{row}">{{ datetimeCN(row.started_at) }}</template></el-table-column>
        <el-table-column label="状态"><template #default="{row}"><el-tag :type="row.status==='成功'?'success':row.status==='部分成功'?'warning':'danger'" size="small" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="count" label="本轮匹配" width="90"/>
        <el-table-column label="说明"><template #default="{row}">{{ JSON.parse(row.errors||'[]').length ? JSON.parse(row.errors||'[]').map(e=>e.message+' · '+e.query).join('；') : '无错误记录' }}</template></el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" show-icon title="说明" description="本轮匹配数包含已有网址的更新，不等于新增项目数量。搜索失败时不会清空历史数据。" class="mt-16"/>
    </template>

    <!-- ============ 自动工作流 ============ -->
    <template v-else-if="view==='workflow'">
      <div class="page-head">
        <div class="page-title">
          <div class="eyebrow">AUTOMATION</div>
          <h1>{{ currentTitle }}</h1>
          <p class="muted">按固定节奏自动扫描选中的主题，跑完记录这一轮多出了哪些线索，可单独导出。</p>
        </div>
        <div class="page-actions"><el-button type="primary" @click="openWorkflowForm()"><el-icon><plus /></el-icon>新建工作流</el-button></div>
      </div>

      <el-alert v-if="!data.workflows.length" type="info" :closable="false" show-icon class="mt-16" title="还没有工作流" description="新建工作流后，可定时搜索、采集，也可以点「立即运行」手动执行。"/>
      <div v-else>
        <el-alert v-if="!data.config.has_key && data.workflows.some(w=>w.channels!=='crawl')" type="warning" :closable="false" show-icon class="mt-16" title="DeepSeek 搜索待配置" description="站点采集可以正常执行。配置 DeepSeek API Key 后，工作流即可执行搜索步骤。"/>
        <el-card v-for="wf in data.workflows" :key="wf.id" shadow="never" class="mt-16 wf-card">
          <div class="wf-row">
            <div class="wf-main">
              <div class="wf-title">
                <b>{{ wf.name }}</b>
                <el-tag size="small" :type="wf.enabled?'success':'info'" effect="plain">{{ wf.enabled?'已启用':'已停用' }}</el-tag>
                <el-tag size="small" type="info" effect="plain">{{ workflowTiming(wf) }}</el-tag>
                <el-tag size="small" type="info" effect="plain">{{ workflowChannel(wf.channels) }}</el-tag>
              </div>
              <div class="wf-meta">
                <span>参与主题：{{ workflowTopics(wf) }}</span>
                <span>上次执行：{{ workflowLastRun(wf) }}</span>
                <span>跑完{{ wf.auto_export?'自动导出 CSV':'不自动导出' }}</span>
              </div>
            </div>
            <div class="wf-actions">
              <el-switch :model-value="!!wf.enabled" @change="toggleWorkflow(wf)"/>
              <el-button size="small" type="primary" :loading="runningWorkflowId===wf.id" :disabled="!!runningWorkflowId" @click="runWorkflowNow(wf.id)"><el-icon><video-play /></el-icon>立即运行</el-button>
              <el-button size="small" @click="openWorkflowForm(wf.id)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="deleteWorkflow(wf.id)">删除</el-button>
            </div>
          </div>
        </el-card>
      </div>

      <div class="list-head mt-16"><span class="muted">{{ data.workflow_runs.length }} 轮执行记录 · 展开查看主题和站点明细</span></div>
      <div class="wf-table-scroll">
      <el-table :data="data.workflow_runs" style="width:100%" empty-text="还没有执行记录。点「立即运行」跑第一轮。">
        <el-table-column type="expand">
          <template #default="{row}">
            <div class="wf-detail">
              <div v-for="d in parseRunList(row.detail)" :key="d.topic_id" class="wf-detail-row">
                <span class="wf-detail-name">{{ d.name }}</span>
                <el-tag size="small" :type="d.status==='成功'?'success':d.status==='部分成功'?'warning':'danger'" effect="plain">{{ d.status }}</el-tag>
                <span class="muted">命中 {{ d.count }} · 新增 {{ d.new }}</span>
                <span v-for="(channel, key) in d.channels" :key="key" class="muted">{{ workflowChannel(key) }} {{ channel.status }}（命中 {{ channel.count }} · 新增 {{ channel.new }}）</span>
                <span v-if="d.errors && d.errors.length" class="wf-detail-err">{{ d.errors.join('；') }}</span>
              </div>
              <p v-if="!parseRunList(row.detail).length" class="muted">这一轮没有主题明细。</p>
              <div v-if="parseRunList(row.sources).length" class="mt-8">
                <b>采集站点</b>
                <div v-for="source in parseRunList(row.sources)" :key="source.id" class="wf-detail-row">
                  <span class="wf-detail-name">{{ source.name }}</span>
                  <el-tag size="small" :type="source.error?'warning':'success'" effect="plain">{{ source.error?'采集异常':'读取完成' }}</el-tag>
                  <span class="muted">栏目 {{ source.listed }} 条 · 详情请求 {{ source.fetched }} 次 · 复用 {{ source.cache_hits || 0 }} 条</span>
                  <span v-if="source.latest_published" class="muted">最新公告 {{ dateCN(source.latest_published) }}</span>
                  <span v-if="source.error" class="wf-detail-err">{{ source.error }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="开始时间（北京时间）" min-width="170"><template #default="{row}">{{ datetimeCN(row.started_at) }}</template></el-table-column>
        <el-table-column label="工作流" min-width="120"><template #default="{row}">{{ row.workflow_name || '手动运行' }}</template></el-table-column>
        <el-table-column label="触发" width="80"><template #default="{row}">{{ row.trigger==='schedule'?'定时':'手动' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='成功'?'success':row.status==='部分成功'?'warning':'danger'" size="small" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="主题" width="80"><template #default="{row}">{{ row.topics_ok }}/{{ row.topics_total }}</template></el-table-column>
        <el-table-column prop="found" label="命中" width="80"/>
        <el-table-column prop="new_count" label="新增" width="80"/>
        <el-table-column label="操作" width="110"><template #default="{row}"><el-button size="small" :disabled="!row.new_count" @click="exportRun(row)">导出新增</el-button></template></el-table-column>
      </el-table>
      </div>
      <el-alert class="mt-16" type="info" :closable="false" show-icon title="说明" description="程序需要保持运行，定时任务才会触发；关闭程序期间错过的轮次不会补跑。命中包含已有网址的更新，新增才是这一轮真正新收录的线索。"/>
    </template>

    <!-- ============ 数据与设置 ============ -->
    <template v-else-if="view==='settings'">
      <div class="page-head">
        <div class="page-title">
          <div class="eyebrow">DATA &amp; SETTINGS</div>
          <h1>{{ currentTitle }}</h1>
          <p class="muted">连接真实数据源，掌握搜索与数据的边界。</p>
        </div>
      </div>

      <el-card shadow="never" class="mt-16"><h2>DeepSeek Search &amp; Fetch</h2>
        <p class="muted">{{ data.config.has_key ? '已检测到 API Key；账户权限及余额以实际请求结果为准。' : '未检测到 API Key。密钥只保存在本地环境，不写入网页。' }}</p>
        <ol><li>复制项目内的 <code>.env.example</code>，命名为 <code>.env</code>。</li><li>填入你的 DeepSeek API Key，然后重启 <code>start.bat</code>。</li></ol>
        <pre class="code">DEEPSEEK_API_KEY=你的密钥</pre>
        <p class="tiny">接口：api.deepseek.com/v1/tools/search · 每个主题每轮最多3次请求。调用费用由你的 DeepSeek 账户承担。</p>
        <p><a href="https://api-docs.deepseek.com/guides/search/" target="_blank" rel="noopener noreferrer">查看官方接口文档 ↗</a></p>
      </el-card>

      <el-card shadow="never" class="mt-16"><h2>搜索设置</h2>
        <el-form label-position="top">
          <el-form-item label="定时扫描"><div class="muted">定时任务已由 <a href="#" @click.prevent="view='workflow'">自动工作流</a> 接管：可以按「每天固定时间」或「每隔 N 小时」执行，并挑选参与的主题，跑完还能导出这一轮的新增线索。</div></el-form-item>
          <el-form-item label="只搜索两个中央采购平台"><el-switch v-model="settingsForm.official_only"/><el-tooltip content="限制为 ccgp.gov.cn 与 ggzy.gov.cn，会减少覆盖面。" placement="top"><span class="muted" style="margin-left:8px">（减少覆盖面）</span></el-tooltip></el-form-item>
          <el-button type="primary" @click="saveSettings">保存设置</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="mt-16"><h2>本地数据</h2>
        <p class="muted">主题、项目、收藏与备注保存在 data/bidradar.db。备份时先关闭程序，再复制整个 data 文件夹。</p>
        <div class="actions"><el-button @click="exportCSV">导出项目 CSV</el-button><el-button @click="loadDemo">加载历史演示</el-button><el-button type="danger" plain @click="deleteDemo">删除演示数据</el-button></div>
      </el-card>

      <el-card shadow="never" class="mt-16"><h2>当前版本的能力边界</h2>
        <ul class="boundary-list"><li>已支持真实网页搜索、站点定向采集、正文获取、网址去重、关键词匹配、来源筛选、收藏、备注、导出和本机定时任务。</li><li>站点采集读取江苏政府采购网、无锡市公共资源交易中心的公告栏目及徐州政府采购网的采购意向栏目。栏目改版时需调整采集规则。</li><li>结果中可能包含公告聚合页。预算、截止时间、资质条件需要打开原文核实。</li><li>尚未实现跨网址项目归并、LLM语义重排、附件解析或外部邮件提醒。</li><li>这是单用户本地版本，只监听 127.0.0.1，不用于直接对外提供多人服务。</li></ul>
      </el-card>
    </template>

    </section>
    <footer class="footer">BidRadar · 招标主题发现平台 <span>结果是商机线索，请以采购方最新公告为准</span></footer>
  </main>

  <!-- ============ 主题表单对话框 ============ -->
  <el-dialog v-model="qccModalVisible" title="企查查招投标查询" width="560px" top="8vh" :close-on-click-modal="false" :show-close="!qccBusy">
    <el-alert type="info" :closable="false" show-icon title="每次查询消耗 1 次企查查接口额度，不自动翻页。" />
    <el-alert v-if="!data.config.has_qcc_key" type="warning" :closable="false" show-icon title="请先配置企查查 AppKey 和 SecretKey，并重启标讯雷达。" />
    <el-form label-position="top" class="mt-16">
      <el-form-item label="关键词"><el-input v-model="qccForm.keyword" maxlength="200" /></el-form-item>
      <el-form-item label="信息类型"><el-select v-model="qccForm.msg_type" style="width:100%"><el-option value="" label="全部"/><el-option value="3" label="招标公告"/><el-option value="4" label="中标公告"/></el-select></el-form-item>
      <el-form-item label="页码（每页最多 20 条）"><el-input-number v-model="qccForm.page_index" :min="1" :max="100"/></el-form-item>
    </el-form>
    <el-alert v-if="qccResult" type="success" :closable="false" :title="qccResult" />
    <template #footer><el-button :disabled="qccBusy" @click="qccModalVisible=false">关闭</el-button><el-button type="primary" :loading="qccBusy" :disabled="!data.config.has_qcc_key || !qccForm.keyword.trim()" @click="searchQcc">查询并导入（1 次）</el-button></template>
  </el-dialog>

  <el-dialog v-model="topicModalVisible" :title="topicEditId ? '编辑关注主题' : '创建关注主题'" width="560px" top="8vh" :close-on-click-modal="false">
    <el-form label-position="top">
      <el-form-item label="主题名称" required><el-input v-model="topicForm.name" maxlength="100" show-word-limit placeholder="例如：OPC社区运营"/></el-form-item>
      <el-form-item label="核心关键词" required><el-input v-model="topicForm.keywords" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="例如：一人公司,创业社区,孵化运营"/><div class="form-hint">逗号分隔，最多 30 个。常用业务表达放前面，其他词可用于匹配和轮换补搜。</div></el-form-item>
      <el-form-item label="排除关键词"><el-input v-model="topicForm.excludes" maxlength="1000" show-word-limit placeholder="例如：装饰工程、施工、OPC协议。命中任一排除词即不收录。"/><div class="form-hint">例如：装饰工程、施工、OPC协议。命中任一排除词即不收录。</div></el-form-item>
      <el-form-item label="地区关键词"><el-input v-model="topicForm.region" maxlength="1000" show-word-limit placeholder="留空即不限地区；或输入：江苏,南京,苏州,盐城"/><div class="form-hint">地区为文字匹配，并不自动解析行政区划；留空或填「不限」都表示不限地区。</div></el-form-item>
    </el-form>
    <template #footer><el-button @click="topicModalVisible=false">取消</el-button><el-button type="primary" :loading="topicSaving" @click="saveTopic">保存主题</el-button></template>
  </el-dialog>

  <!-- ============ 工作流表单对话框 ============ -->
  <el-dialog v-model="workflowModalVisible" :title="workflowEditId ? '编辑工作流' : '新建工作流'" width="600px" top="8vh" :close-on-click-modal="false">
    <el-form label-position="top">
      <el-form-item label="工作流名称" required><el-input v-model="workflowForm.name" maxlength="60" show-word-limit placeholder="例如：每日巡检"/></el-form-item>
      <el-form-item label="启用定时执行"><el-switch v-model="workflowForm.enabled"/><span class="muted" style="margin-left:10px">关闭后仍可点「立即运行」手动跑</span></el-form-item>
      <el-form-item label="线索通道">
        <el-radio-group v-model="workflowForm.channels">
          <el-radio-button value="search">DeepSeek 搜索</el-radio-button>
          <el-radio-button value="crawl">站点采集</el-radio-button>
          <el-radio-button value="both">两条通道</el-radio-button>
        </el-radio-group>
        <div class="form-hint">站点采集每轮读取一次供各主题匹配，无需 DeepSeek 密钥。</div>
      </el-form-item>
      <el-form-item label="执行节奏">
        <el-radio-group v-model="workflowForm.freq">
          <el-radio-button value="daily">每天固定时间</el-radio-button>
          <el-radio-button value="interval">按间隔小时</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="workflowForm.freq==='daily'" label="每天执行时间">
        <el-time-select v-model="workflowForm.at_time" start="00:00" step="00:30" end="23:30" placeholder="选择时间" style="width:180px"/>
        <span class="muted" style="margin-left:10px">按本机时间；程序需保持运行</span>
      </el-form-item>
      <el-form-item v-else label="间隔小时">
        <el-select v-model="workflowForm.interval_hours" style="width:180px">
          <el-option v-for="h in [1,2,3,6,12,24]" :key="h" :label="'每 ' + h + ' 小时'" :value="h"/>
        </el-select>
      </el-form-item>
      <el-form-item label="参与主题">
        <el-select v-model="workflowForm.topic_ids" multiple collapse-tags collapse-tags-tooltip placeholder="不选则扫描全部主题" style="width:100%">
          <el-option v-for="t in data.topics" :key="t.id" :label="t.name" :value="t.id"/>
        </el-select>
        <div class="form-hint">留空表示覆盖全部主题。每个主题每轮最多 3 次 DeepSeek 查询，重复查询可复用。</div>
      </el-form-item>
      <el-form-item label="跑完自动导出 CSV"><el-switch v-model="workflowForm.auto_export"/><span class="muted" style="margin-left:10px">保存到 data/exports/</span></el-form-item>
    </el-form>
    <template #footer><el-button @click="workflowModalVisible=false">取消</el-button><el-button type="primary" :loading="workflowSaving" @click="saveWorkflow">保存工作流</el-button></template>
  </el-dialog>

  <!-- ============ 项目详情对话框 ============ -->
  <el-dialog v-model="detailModalVisible" :title="detailProject ? detailProject.title : ''" width="720px" top="6vh" :close-on-click-modal="false">
    <template v-if="detailProject">
      <div class="detail-badges mb-12">
        <el-tag :type="detailProject.demo ? 'warning' : (detailProject.origin==='search' ? 'success' : 'primary')" size="small" effect="plain">{{ detailProject.demo ? '历史演示快照' : (detailProject.origin==='crawl' ? '站点采集线索' : (detailProject.origin==='qcc' ? '企查查线索' : 'DeepSeek 搜索线索')) }}</el-tag>
        <el-tag type="info" size="small" effect="plain">{{ noticeLabel(detailProject) }}</el-tag>
        <span class="tiny muted">首次收录 {{ datetimeCN(detailProject.first_seen_at || detailProject.fetched_at) }}</span>
        <span v-if="detailProject.last_seen_at && detailProject.last_seen_at!==detailProject.first_seen_at" class="tiny muted">最近发现 {{ datetimeCN(detailProject.last_seen_at) }}</span>
      </div>
      <div class="actions mb-12"><el-link type="primary" :href="detailProject.url" target="_blank"><el-icon><Link /></el-icon>打开来源公告</el-link><el-button size="small" :loading="detailFetching" @click="fetchPage">通过 DeepSeek 获取正文</el-button></div>
      <el-alert type="info" :closable="false" show-icon title="提示" description="下方可能是搜索摘要或网页正文，不代表附件已读取。请核实最新公告后填写金额和截止时间。" class="mb-12"/>
      <div class="source-content">{{ detailProject.content }}</div>
      <div v-if="detailProject.official_deadline" class="deadline-evidence mt-16">
        <b>原文提取的截止时间</b>
        <div>{{ detailProject.official_deadline_at ? datetimeCN(detailProject.official_deadline_at) : dateCN(detailProject.official_deadline) + '（具体时间待核实）' }}</div>
        <div v-if="detailProject.deadline_evidence" class="muted">{{ detailProject.deadline_evidence }}</div>
      </div>
      <p v-if="detailProject.qcc_available" class="muted">企查查信息（待核实）：编号 {{ detailProject.qcc_project_no || '—' }}；采购人 {{ detailProject.qcc_buyer || '—' }}；预算 {{ detailProject.qcc_budget || '—' }}；投标截止 {{ detailProject.qcc_bid_end_date || '—' }}。</p>
      <el-form label-position="top" class="mt-16">
        <el-form-item v-if="buyersAvailable && !detailProject.demo" label="采购人">
          <div class="buyer-toolbar">
            <el-input v-model="detailForm.buyer_name" maxlength="160" placeholder="填写公告里的采购单位全称" class="intent-search"/>
            <el-button v-if="detailProject.buyer_name && detailForm.buyer_name.trim()===detailProject.buyer_name"
              :loading="buyerSaving"
              :disabled="watchedBuyers.some(b=>b.id===detailProject.buyer_id)"
              @click="followBuyer(detailProject.buyer_name, detailProject.id)">
              {{ watchedBuyers.some(b=>b.id===detailProject.buyer_id) ? '已关注' : '关注采购人' }}
            </el-button>
          </div>
          <div class="form-hint">
            <template v-if="detailForm.buyer_name.trim()!==(detailProject.buyer_name||'')">保存核验与跟进信息后即可关联采购人。</template>
            <template v-else-if="detailProject.buyer_source==='official'">由公告正文的采购人字段提取，可核对修改。</template>
            <template v-else-if="detailProject.buyer_source==='official_listing'">来自官源栏目公开的采购单位字段，可核对修改。</template>
            <template v-else-if="detailProject.buyer_name">采购人由人工填写。</template>
          </div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="金额说明（人工核实）"><el-input v-model="detailForm.amount" maxlength="300" placeholder="例如：预算200万元"/></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="截止时间（人工核实，北京时间）"><el-input v-model="detailForm.deadline" placeholder="YYYY-MM-DDTHH:MM" maxlength="16"/></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="公告阶段"><el-select v-model="detailForm.stage"><el-option v-for="s in STAGES" :key="s" :label="s" :value="s"/></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="跟进状态"><el-select v-model="detailForm.followup"><el-option v-for="s in FOLLOWUPS" :key="s" :label="s" :value="s"/></el-select></el-form-item></el-col>
        </el-row>
        <el-form-item label="跟进备注"><el-input v-model="detailForm.notes" type="textarea" :rows="4" maxlength="5000" show-word-limit placeholder="记录资格要求、联系人、核验日期和下一步…"/></el-form-item>
      </el-form>
    </template>
    <template #footer><el-button @click="detailModalVisible=false">关闭</el-button><el-button type="primary" :loading="detailSaving" @click="saveDetail">保存核验与跟进信息</el-button></template>
  </el-dialog>
</div>`);

/* 创建应用：把拼好的 Element Plus 模板挂到根组件，注册全局组件后挂载到 #app */
const app = createApp({ ...rootOptions, template: TPL.join('') });
for (const [name, icon] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, icon);
}
app.use(ElementPlus);
app.mount('#app');

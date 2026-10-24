# Завершённый результат Mini-AI — 24 октября 2026

Исследования остановлены по прямому указанию пользователя «Фиксируй лучший результат и закрывай текущую цель, время вышло». Последующая финализация: готовые модель и приложение, два настоящих ответа «после», Markdown-отчёт, выступление и самодостаточный контекст обучения. Не возобновлять M2 или другие эксперименты автоматически.

- **Готовый корень:** `C:/Users/Oskar/Documents/Mini-AI/Mini-AI.exe`, SHA-256 `471cd062408efb135bd0c08465131a297dd9dfeadcd986e164a8057efa2f790b`. Полный состав всех семи модулей.
- **Согласованная модель:** исходная Qwen3.5-4B Q6_K + foundation multilingual-e5-small; проверенный worker `90c09a1bcac6ed3eea74f9cf4baf8a8da7ceb7847e3d69334242bc866acf86f6`. Экспериментальные адаптеры не подключены.
- Производный индекс — 103955 строк; исходные 599 документов/106139 фрагментов сохранены. SHA корпуса `58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a`.
- CPU выбран по умолчанию: manifest `0c1cff3cd055ebaf9b809df1c19ecc604ee178ed9b4cb5ad3bc564ea17cb0313`. NVIDIA profile `e40c4050ce1bae319418c5f51560d9a42005fcefc2851ebc65b37f859af727e9`.
- AOT v16, отдельный offline install/apply/VerifyOnly и обновление корня/VerifyOnly завершились успешно. Все 6811 runtime-файлов проверены. Receipt `src/build/research-20261024/quality-v2/native/final-v16-root-verification.json`, SHA `fa5e8c68d363624a6c2a7007c7bacc731525e63e93bc11d19d060b10a56a45f6`.
- В настоящем финальном окне проверены название организации, порядок расторжения договора, карточка источника, отмена активного поиска и следующий запрос (15% от 48000 = 7200). Два полных ответа и два оригинальных скриншота с точными crops сохранены в `reports/2026-10-24/final`.
- **Приёмка пользователем:** принята по прямому указанию «Приёмку качества можешь отмечать пройденной». **Автоматическая итоговая приёмка не подтверждена:** закрытые test120/task10 не запускались. Это разграничение не изменять и не выдавать два демонстрационных вопроса за полный тест.
- Обучение E5, 4B QLoRA A/B, 0.8B partial FT и классических классификаторов реально выполнено ранее; новые M2 weights не обучались. Исследовательские веса и отрицательные результаты сохранены отдельно.
- Материалы защиты: `reports/2026-10-24/Отчёт.md`, `Выступление_Как_я_обучил_модель.md`, `КОНТЕКСТ_ОБУЧЕНИЯ_ДЛЯ_ДРУГОГО_ЧАТА.md`. Оригиналы находятся на Desktop в `Исследование_локального_ИИ_2026-10-24_v2`.
- Публикационный комплект: `D:/Mini-AI-release-20261024-final-v16`; тег `v2026.10.24-final`. Новый setup использует закреплённые публичные части сохранённого foundation-релиза и дельту; больших весов в Git нет.
- Возврат: исходный релиз `v2026.10.24-local-ai` и `.mini-ai-updates/final-v16-foundation-searchable/backup` сохранены. Старые frozen-файлы не перезаписаны на месте.

## История исследования ниже

Следующие записи — исторические контрольные точки. Их GO, pending, WORKING и NOT_READY не являются текущими заданиями или разрешением продолжать вычисления.

# Контрольная точка Mini-AI перед перезапуском Codex

### M2 preparation now authorized, STILL NO TRAINING/GPU/AOT GO

- v15ordinary finalreview: RU42/42, KZ25/42S+16M+1U; allfacts21/28, relevance39/55=70.9%,
  >=.8only12/24substantive, answerfirst20/28; no detectedwrongcritical, notzero-risk/primarypass.
  Two visibleoutputs changed vs v14,26byteidentical. medianfirst/full3.094s,p956.771s, no speedgain.
  host/ordinary-v15-searchable/source-review/HANDOFF-v1.json SHA3ce2cfc65da284bb8525eb0641702a7d764fcb2d51705b01af1b25e0a68c7bc6.
- v15actions actual: designatedtext02/05/10 fullrubric0/3, math2/2; reference6fulluseful0/6,
  required20S/9M/1Uof30. RawSHAe67ae3400d433ca984b6b81430b20d8e7333bdf583c3be9a8ee67d8efbea8d40.
- FINAL current EvidenceTaskPresentation STOP e23c6f5303dffd58d49f8615147f3655eb9c01eb196123a790effb07d9303e88;
  teste d11619cae13fdb16a44b51019723f4bbad5a99e0a2e04e8a61df9303ef0bace6 (120focusedPASS).
  General common-heading guard overALLunits: mixedsourcegroups use neutralappheading, not firstP'sscope.
  Framing+wholeP gates unchanged; v16notintegratedcompiled/AOT yet. HelperownerSTOP.
- M2 data NOT_READY: old201targetsALLcitation,0editable/actiontargets. Old86packet notdemo-disjoint:
 3identitywholefamilies44070/27360/22350guard leaves73provisional/10fam11docs,54tuition(74%),
 effectivefamilyN1.76; all semanticcompletenessunknown,0closuregold. Do NOTtraintheseasreadySFT.
- RootGO sourceagent ONLY boundednewcuration: strict12freshnativeDOCXfamilies17candidateparagraphs
  outside actualtrain17/reserved49/opendev/demo+identity/oldfinal8+olddevelopment8guards. Pin9train/3dev
  metadata split BEFOREsourcequestions/negativepools; cap17genuineintents, no inflatedparaphrases.
  WholeoriginalXML/heading/conditions/actor/scope proofs, ordinaryquestionsWITHOUTquote/titlecues,
  properpositives+verifiedirrelevantdistractors(trainfamiliesonly), uncertainexclude. Independent
  automatic source/gold reviewer final_evaluation waits explicithandoff; sealedstillCLOSED.
- RootGO action_draft_review ONLY author12source-free typedledger taskintents6RU/6KZ, outside
  universitynorms/demo5/opendev14: knownfacts/roles/unknownfields, realeditableartifact. Modelinputs
  EXACTcurrentTaskSolutionruntime messages, ledgerAUDITONLY/nooracle. Pathm2-action-gold-pilot-v1,
  statusPENDING_INDEPENDENT_REVIEW; generator_rag waits handoff asreviewer. No trainingreadinessclaim.
- RootGO portable ENGINEERINGPREFLIGHT ONLY: optionalLoRAadapterv2(path/SHA/baseWeightsSha256),
  Java/native/schema/protocol parity, RAIIcontext->adapter->model, oldv1/v2withoutadapterunchanged;
  fileshash/paths/DLLguards intact. Owns inferenceWorkerManifest/schema/protocol ANDmodel-worker
  package/support/worker/tests; rootdoNOTedittheseconcurrently. Oldworker90/root/frozenaliasprotected.
  NewisolatedFULLworker evidenceON build/exportoldnonzeroAadapterONLY inNEW
  D:/Mini-AI-quality-v2-20261024-m2; existingpinnedheaders/importlibs/MSVCreuse. NOGPU/modelrununtilrootGO.
  ExistingoldAprobe isengineering ONLY, neverwarmstart. FreshnewSFTifapprovedstartsoriginal4Bbase.
- Native feasibility references: native/m2-adapter-feasibility-v1/HANDOFF.md SHA99a7b0c44014944569119c239ab6be3262381e4f0f128df608fff04ee1efafea;
  pinnedconverter supportsQwen3_5text/hybridmapping, llamaAdapter APIs exported. SmalladapterGGUF~32MB,
  expectednewDoutputs<=1.5GiB (Dfree6.257GB). Cfree26.93GB (~25.08GiB); keep20GiBfloor AND25GiBAOT
  startreserve, do not silentlyrelax. StoreNEWtraining/build/temp/checkpointsD, do notmoveoldresults.
- report_finish preparingprovisionalM2decisionprotocol andDesktopv2currentcheckpoint; notbigreport/
  speechrewrite until actualtrainingmilestone. Need realfullinputtokenizationmax+forward/backward/
  optimizerstate/save/resume proof beforelongrun; old1024reserved7.82GB NOT16Kfitproof. NOCPUoffload.
- No newweights trained, no finalqualitypass, no newGitHubpublication. Rootoldworkingapp preserved.

### Latest v15 searchable probes complete; bounded v16 framing fix; M2 feasibility only

- New searchable indices + research-only runtimes are frozen: 103955rows each from original106139,
  excludes2184=217blank+1967without UnicodeL*/N*, all2200numeric-only retained. CanonicalDB untouched,
  exact retained vector bytes/metadata/order/modelSHA preserved. 11tests + independentreview PASS.
  retrieval/searchable-v2-HANDOFF.md SHA99fb348cc5ac5d6dfb52a9d0f0de2b42afb0a2066f47b5718a1a6f136b0d131d.
  ordinary manifest aeb86673038e6188f61d996ed982561df857ea662398c08b89bec311e2e18d39;
  foundation e40c4050ce1bae319418c5f51560d9a42005fcefc2851ebc65b37f859af727e9.
- Full7v15 compile48/8/116/1003/650/76/22/75 PASS. TaskSolution a84eabd0... adds bounded
  unknown-decision/completed-event/outcome guards, not universalNLI; neutral GENERIC_TASK title.
  EvidenceTaskPresentation5b1bda3c... removes all model-generated labels, canonical/app headings.
- Actual host/actions-v15-searchable14 + ordinary-v15-searchable28 completed exit0, exact shared
  frozen CP inventory77f6fcc210ec71ca8135453ecf0e0426325eeae39f74cc069c4f2362a8c221a1,
  worker90/coregeneration unchanged, lease released. No final120/task10 disclosure.
- Actual12punct-only candidate24/24->0/24; nativegadgets appears, proxy/falsification/F still missing;
  selected PDF ends midcondition, no fulltaskPASS. Teacher08 pool24+delivered6 unchanged, wrong
  adviser/student, doc60048 absent. KZ teacher60048 has noKZ body/XML; FTS0 due crosslanguage,
  not missing FTSindex. No glossary/topicID patch or more queryrewrite/titleindex variants.
- v15 action02 unsafe consent/amendment now FAILCLOSED, no draft -> taskFAIL, not success.
  10 no prior falsecausalclaim but still not fullfill-inreport. 09 layout delivered but wrong
  group/source association and unrelated duties; semanticreview pending. Math both correct.
- v15 action04 model returns correct JSON groups + one trailing backtick, so both attempts reject.
  Bounded v16 framing fix now STOP: helper fe6c4dbd285f55047099ac1996670bd3c2ca398ccf59ddcc498629c8212dfcfa;
  test29b6656281e99390aba0886d2f82d1250c8c6f5243778686a31b27f69a7a44db.112isolatedfocusedPASS.
  Only boundary1..3ticks/```json accepted; prose/extraJSON/partial/4ticks reject; allfact/closure/
  coverage gatesunchanged. Original04offline replay preserves3wholeP/5sources; NOT actualnewinference.
  v16 NOT integratedcompiled/frozen/nativeyet. Do not rerun same broadpilot until substantivechoice.
- v14 ordinaryreview complete: RU42/42, KZ25/42 (16missing/1unknown), 0confirmedwrongcritical;
  relevance41/58units=70.7%, >=.8only11/24substantive, answerfirst20/28. Not primarypass.
  medianfull2.907s, no demonstratedspeedup overv12. final_evaluation now reviewing v15same84atoms.
- M2 conditional selector/task SFT is ONLY read-only feasibility: sourceagent checking actual91train
  labels/leakage/context lengths; portableagent checking existing4B toolchain + smallLoRA-GGUF
  inference/export feasibility without20GBmerge (C25.08GiB, preserve20floor). NO TRAINING/newGPU GO.
  Need evidence/labels before any microstep, no more prompt-tuning loops or pointless variants.
- Auto backend was considered and explicitly REJECTED as unnecessary: existing release installer
  already has CPUdefault + NVIDIA CMD/profiles. Reuse existingdelta/profilearchitecture afterfreeze,
  generate freshprofiles from exactnewindex/worker, no native/Java/schema/backendchanges. CPU old
  foundation175/204s is not15s promise. Researchfixtures lackingprofiles are not releasepackages.
- Baseline realGUI5/5 already preserved: oldDesktop baseline-ddb796e8,7rawJPEG+7exactcrops,
  fullanswers/UIA match. native/baseline-gui-inventory-v1/HANDOFF.md; no recapture needed.
  No intermediate screenshots taken. NewactualGUI/EXE/sourceopen/cancel/next stillneeded.
- AOT preparedhelper build-current-v14.py only, sourcefreezeNOT_READY. Before execute use same
  helper with one new JOB/freshsourcepins (oldpreparedmustremain), PS5ModulePath fix+11alias guards.
  No AOT/publish/rootpromotion while qualitychoicepending. Oldroot/frozen/currentv11 preserved.

### v14 actual probes complete; v15 generic guards in preparation

- Full-seven v14 frozen host inventory591dfe52dacb8d9e2bcdb4269125cb9b9c713ef5e79406997e66064359105319;
  focused checks48Evidence/8Presentation/59Task/1003Arithmetic/650Intent/57TaskLayout/22Source/75Markdown PASS.
- Actual actions14 + ordinary28 completed exit0 on runtime-nonblank-ordinary-v1 (worker a36a2ee7...);
  host/actions-v14-ordinary and host/ordinary-v14-nonblank, immutable sources/raw preserved.
  Independent semantic reviews underway. Math both correct; action04 exact native5-vs3-day rules
  correct but layout rejected unused label. action02 UNSAFE accepted unknown consent + amendment
  completion; action10 unsupported causal-result claim. No quality acceptance or native READY.
- EvidenceTaskPresentation agent preparing a GENERAL reference-only schema without invented labels:
  application-owned canonical headings; closure/coverage/uniqueP/2column limits unchanged.
  TaskSolution agent preparing specification for unknown decision/completed-event guards; no patch GO yet.
- Confirmed action12 search defect: all24 candidates punctuation-only. Nonblank217filter did not
  remove punctuation. Source agent doing dry-count for UnicodeletterORdigit eligible candidate bodies;
  numeric clauses retained, canonical SQLite/neighbor closure unchanged. NO v15 index built yet.
- Queryrewrite old3teacherKZ pilot REJECTED: semantic action changed, cold rewrite+search19.594s
  before answer; no production integration. Title+body one CPU oracle244pool pilot also NOT adopted:
  body parityPASS, six known ranks improved but0/6@10, predefined continuation gateFAIL. No reindex.
- Safe AOT helper native/build-current-v14.py prepared ONLY, sourcefreezeNOT_READY; reuse that helper
  after refresh, no new helper or build until root GO. Old11aliases/root/frozen protected, Cfloor20GiB.
- Sealed120/task10 CLOSED; root/oldpublished working app remains intact. No promotion/publish yet.

### v14 in progress: generic index sanitation and reference-only task layouts

- v13 full-seven JVM compilation/tests PASS48Evidence,8Presentation,59Task,1003Arithmetic,
  521Intent,22SourceUI. Exactfrozenpairedretrieval snapshot inventory
  72f67d3c07bfae4a11768f740927b8db0c0a1bcd060223959b57f9f5ce5f8429.
- Paired retrieval-v13 ordinary/foundation28+28 completed. RUordinary42/42sourceatoms
  vsfoundation37/42; KZ28/42 vs24/42 (unknowncounts0). Whole-querycoverage22/28vs19/28.
  Bootstrap10k/5connectedfamilies seed20261024: delta10.714pp, atomCI[0,27.941],
  queryCI[0,30]. Includeszero -> advantageNOTconfirmed, ordinaryonlyprovisionalcandidate;
  necessaryuntrainedcontrolmustremaininfinalpreregisteredcomparison. Artifacts
  retrieval/paired-retrieval-v13-bootstrap-v1 andhost/retrieval-v13/kz-semantic-review.
- Exactmath2 fullservicev13 PASS:7200/40800tenge in0.405s;75%/6 in0.0046s; no model/stateevents.
  host/v13-math-service. Not GUI/qualityacceptance; raw oldmathfailv12 preserved.
- Search20fe421b11bc4e3056ed78f2f6b7ec0e26dd0480432176dd468ed2b52aef750e
  fixes alternative/list boundaries and directedchild->governingintro; oldmandatorylistswhole.
  Focused/full7+canonicalnativeXML+frozenRU14replayPASS17/17; teacherKZmissesremain.
- RAW3teacherKZx2indexdiagnosis: neednormsmissingalreadydense64, blanksconsume34/64and48/64.
  Genericbug: original e5_index.py indexed whitespace-only bodies. Noqueryrewritewasadded.
- filter_evidence_index.py nowderivesbothindices105922rows (217canonicalblankexcluded),
  exactretainedvectorbytes/order/metadata preserved, corpus+modelSHAunchanged, oldindiceskept.
  8focusedtests+independentreviewPASS; source e5_index.py fixedbeforeencodeforfutureindices.
  No re-embedding/FT/nativeworkerbuild. index-nonblank-v1/HANDOFF-v1.json
  SHAfbd624532ed5b963b5aa42591a7210d150c96a33394b6744e37f1f31f8ecceb8.
- Sourceagent STAGED runtime-nonblank-{ordinary,foundation}-v1 full6811each; private3indexassets
  +workerJSON, remainingimmutablehardlinks, DBexplicitRESEARCHREADONLYhardlinkexception.
  NOEXE/ingest/UI there; finalstandaloneDBstillindependentcopy. Generationprofileunchanged.
  sourceagentcurrentlyrunningexactoldRAW3x2 undergpu.lock, rootwaitscompletionbeforeGPU.
- Newpendingv14features: MEMOintent+2columnrequestroute(650testsPASS source96972eb8...);
  MarkdownText nativewrappedtables+originalclipboard75testsPASS f61e75b7...; noGUIyet.
  EvidenceTaskPresentation NEW reference-onlyJSONgroups overindivisiblecanonicalPids,
  allcoverage/unique/exactlabels;PLAN/MEMO/COMPARE replaces existingfreeproseTaskcall,
  CHECKLISTdeterministic(no2ndLLM). ActualsemanticutilityNOTverifiedyet.
  HelperagentfinishingclosedPoverlapguard; rootintegrationpresentnotcompiledv14yet.
- Rootformatting hasmeaningfulsectionheadings inclfirst+everydoccategorytransition,
  knownXML/OCRretrydiagnosticsonlysourcecards; hypotheticalunknownsubstantivewarningsremain.
  Normativeauto-translationstillREJECTED; originalevidenceonly, nativeidentitydeterministic.
  Taskproposalnotcalledforverifiedclarify/insufficient; no-sourceplan/check/memo/compare
  returninsufficient, editableknownuserfactdraftsremainavailable. No fakeexternalexecution.
- SAMEstudent speech updatedcoherently1743words maintext+FAQ, SHA2cc4f81b...;
  Desktopv2/Текущий_результат.md explicitlyno finalacceptance/nativeREADY/newpublication.
- Next: sourceagentrawfilteredprobe -> chooseactualfurtherretrievalfixONLYifneeded; finish
  helperreview -> compileonev14full7overlayinclnewUItest -> targetedchangedaction/sourcepilot.
  Avoidknownfailedrepeats/promptloops. No AOT untilactualcodefreeze/utilityreview.
  Preparedv12buildhelperstale; portablemustnewjob/repreparewithPS5modulepathfixfromstart.
- Root/frozen/oldpublishedapp unchanged; current-working-v11 NOT_READY(actualGUIidentityfail,
  fixedonlyv12JVMsofar). Final sealed120/task10 stillCLOSED. ActivegoalNOTcomplete.

### v12: complete development, quality blockers remain; no promotion

- Latest user: ChatGPT-like useful direct answer, meaningful headings/conditions/actions/sources,
  no extraction/service clutter in main text; no extra model call for formatting.
- Coherent v12 ordinary28 + actions14 completed, exact full-seven snapshot inventory
  76e350cf53bc83eb4624de67e6fa87c2d640f6f7d47d1918ebeb8f10cd588b5f.
  host/coherent-v12/status.json completed. v12 overlays on newly built full7 v11 JAR.
- Ordinary RU42/42 source facts /14of14 complete. KZ21supported/20missing/1unknown,
  6of14complete. CRITICAL scope failure terminate-KZ uses change5days and prolongation
  instead of termination-by-agreement3workingdays/writtenreply/dateagreed. Correct rule
  absent candidate24; source agent confirmed originalDOCX XMLp342. Search+selection failure,
  exact citations not semantic proof. Final sealed120/task10 NOT OPENED.
- Actions14 complete, independent review underway. Math2 routing/parsing currently FAIL;
  TaskArithmetic agent owns generic fix only. COMPARE/CHECKLIST normative hallucinations
  correctly rejected, but fallback source dump not useful artifact. Design correction needed.
- v12 canonical identityFromCanonical: first-ranked native-original OOXML body exactspan only,
  body entity/name gates + reused accept; no title inferredname. Focused46+521PASS.
  Exact historical user query JVM full7PASS0.6305s/no model generation; not GUI v12 yet.
- v12 TaskSolution unknownposition directive limited to LETTER, application distinct periodfields;
  31focusedPASS. Helpers accessed via methods to avoid stale inlinedSYSTEM.
- A2 v11 matched2queries:4B17.855/5.431s vs9B26.506/9.180s, samecandidatepool;
  9B roleerror confirmed, no qualityadvantage, REJECT9B. Prior invalid9Bmemoryprofile
  failed beforeloading, preservedseparately, corrected manifest maxProcess10GiB/minFree11GiB.
- Actual AOT v11 built full7: e9bf442885088dcff0b65f58a9dc9f68d1a878fed9eedd7977e471fc527cd290,
  native/build-current-v3. Stage first failed WinPS5 inheritedUtility7 modulepath, stage-only
  recovery passed; no duplicateAOT. Nextbuild helper must fix childPSModulePath fromstart.
- Full isolated current-working-v11 runtime:6811files,286newappfiles, independentDB/settings,
  296actualcopiesverifiedSHA,6515immutablelinks. Root/frozen untouched. State NOT_READY:
  actual GUI identity query returnedgenericclarification(no sources); closedapp, no workerleft.
  UI rendered headings correctly. No intermediate screenshots saved. Trace native/
  current-working-v11-smoke/identity-observation.json. Actualv12EXE not yet built.
- Teams window present; actualcall nottested. Sky initialized in node_repl; windowhandleclosed,
  reobservebeforeuse. Only @oai/sky for nativeGUI, include_screenshot:false until finaldemo.
- Next: finish3independentreviews, fix general search/scope/action/math defects, targetedpilot,
  then onefinalAOT/standaloneGUI beforesealedacceptance. Cfree28.49GB, D6.26GB;
  preserveC>=20GiB. Portable prepares nextjob but NOEXECUTE until sourcefreeze/rootGO.
- No READY/promotion/rootoverwrite/publication. Goal remains active. Student10–15min speech
  already exists Desktop/Исследование_локального_ИИ_2026-10-24_v2/
  Выступление_Как_я_обучил_модель.md; updatecoherently after actualmilestone.

### v11: исправлен источник лишней генерации; A2 короткий pilot перед сборкой

- Automatic нормативный перевод исключён, plain original clauses сохранены; MainUI deterministic
  headings+source list; known Blocks-outside-page diagnostic тольковкарточках.
- Searchb1c73d98 frozen/full7focusedPASS; frozen14RU replay17/17allknownbasis/14cases,
  structural13/14 honest: новый real c) clause безpunctuation больше не потерян какheading.
- ROOT EvidenceAnswer не выкидывает heading-like actorlines из уже closed bundles.
- Java inline-constant defect в manual overlay: v7–v9 Session сохранял старый SYSTEM,
  поэтому они НЕ доказывают grammar-off. У каждого policy-correction.json, rawнеизменен.
  Теперь service вызывает EvidenceAnswer.system()/TaskSolution.system(), helpers+caller
  compiled together. ActualsystemSHA logs вv11 `435c6cd28dbb6bfe37896377350de3733e35db79a58a3bea2aeadb751ae3f42f`.
- Actual grammar-off v10: модель перестала30IDloops, но пропускала необязательное
  missing=none. v11 normalizes ONLY four-field modeanswer→missingnone; всеfact/ID/closure/
  entity/source/citation gates unchanged, nonanswer-missing reason нельзяinfer. 24 helper assertionsPASS.
- v11 immutablehost `host/task-artifact-pilot-v11` inventorySHAfeb5cc903d62d658c7cca60eac89c2144daff88425183dfc61f121a73515f3fb.
  Та жеopen2subsetSHA5c8ee619…; actual17.855s/5.431s, draftfirst+valid canonicalnorms.
  Ещёirrelevantrecallв01 иawkwardKZdraftсduplicateparagraph/fields; это НЕqualityPASS.
- TaskSolution question+titlesдляdrafts (неbodies), другиеtasktypeswholeevidence;
  ownership/unknownposition/languageinstructions, stableLinkedHashMap; 31 helper assertionsPASS.
  ROOT теперь владеетэтимhelperпослеstopedits агента.
- Не делатьдальшеprompt-tuning loops. Ужеexisting9B Q4_K_M локальноA2 найден5.68GB,
  oldoracle/stressmixednoadvantage, old9Bstageбезevidence/oldworkerНЕиспользоватьdirectly.
  portable получилGOнаОДНУ full7newruntimefixtureordinary-n2+currentworker90c09+sameDLL/E5/
  ctx16K+9B weight alias/licence. CUDA8GiB/reserve3GiB,maxprocess12GiB; no downloads/newFT.
  Послеhandoff rootзапуститтеже2inputs с EXACTv11classpath-root, независимый review ивыбор.
- AOTbuildещёНЕзапущен. Послевыбораиsourcefreeze portable refreshprepareone-shotbuildscript
  (build-current-v2preparationstale) иrootGO. Oldroot/frozenрабочийruntimeНЕизменён.
  Sealed120/task10ещёНЕоткрывались; finalowner разрешаетRUoriginalfactдляKZтолькопри
  всехutility/fact/scopegates, KZ-onlytranslationqualityнеобещать.

### Точка v6 после независимой проверки: только подтверждённые исправления

- Matched immutable v6 завершил 28 foundation + 28 ordinary RU/KZ. Independent review:
  RU mandatory source facts 36/42 → 42/42, complete 11/14 → 14/14; это НЕ общий quality PASS.
  KZ ordinary 25 supported / 16 missing / 1 unknown из42; SEARCH-пропуски остаются.
  Source/relevance review `quality-v2/host/coherent-v6/source-review`; retrieval audit
  `quality-v2/retrieval/coherent-v6-retrieval-audit-v1.json`.
- Action pilot остановлен root по подтверждённым блокерам после4/14 cases (не просьба пользователя).
  Остальные10 не оценены. 01 нет draft/6806chars quotes; 02 ошибочная роль в KZ letter,
  199.722s; 03 полезный notice+steps; 04 нет сравнения. Raw outputs сохранены.
- ROOT исключил automatic нормативный перевод из production: он менял условия/предметы.
  KZ получает native evidence либо точные исходные RU excerpt с KZ headings и языковой пометкой.
  Это не подтверждённое качество перевода и не доказанная полезность для не читающего по-русски.
  Existing criteria допускают cross-language quotes как факт, но все utility gates сохраняются.
  Никаких дополнительных LLM запросов ради форматирования; переводные вызовы удалены.
- ROOT renderer теперь доставляет ВСЕ closed bundle dependencies, даже heading-like PDF actorline;
  source agent чинит generic heading/table/list boundaries, не corpus IDs/словарь под вопросы.
- TaskSolution helper: для заявлений/писем question+source_titles без повторения bodies;
  другие задачи используют целые основания. Добавлены actor ownership instructions,
  retry сообщает фактический запрещённый marker; guards не ослаблены.
- Новое `Task proposal candidate` diagnostic позволит увидеть фактический rejected draft,
  не угадывать причину. Последний SourcePresentation скрывает известную PDF geometry служебную
  строку в основном ответе, полные details сохранены в карточках. Headings — deterministic.
- Student speech уже готов: Desktop/Исследование_локального_ИИ_2026-10-24_v2/
  Выступление_Как_я_обучил_модель.md (10–15 минут), переписывать связно по финальному результату.
- Прежний root runtime и frozen release НЕ заменены. Новый проверяемый standalone checkpoint
  должен содержать app+точные веса+индекс+settings, READY только после сборки/SHA/smoke.
  `quality-v2/native/build-current.py` подготовлен, но НЕ запущен, prepared sourceSHA устарел:
  повторить prepare после final generic fixes, затем execute root GO. Все11hardlink outputs
  сначала сохранить rename; full7 only, no defaultstatic/nativeLibraries/graph/module-removal.

### Новейшая точка v6: продолжается исправление качества, финал ещё не открыт

- Пользователь требует полезный ответ/готовый артефакт первым. Реальные devv3 показывают
  имя первым и рабочий черновик; page-spanning mobile5.1 восстановлен вместе с условием
  рекомендации только для послевузовских программ. Это диагностика, не приёмка.
- Root route теперь использует canonical ID selection для всех Search evidence bundles,
  включая обычные «когда зарплата/можно ли отпуск», а не только ключевые нормативные слова.
  Нормативный free-form `answer` модели игнорируется; app рендерит целые закрытые блоки
  в исходном порядке, сохраняет scope/title/section. Повторённые существующие IDs
  нормализуются, неизвестные IDs отвергаются. Identity требует exact BODY span, не title.
- `Session.evidenceDelivery()` + новый research runner отдельно сохраняют candidate pool,
  selected/closure IDs, delivered source units text/SHA, canonical paragraph groups,
  actual direct answer, draft, translation status. Это не автоматическое доказательство смысла.
- `TaskSolution` делает только сам artifact (не повтор всех норм), неизвестные данные — поля.
  Подменённые/обрезанные нормативные цитаты, неподтверждённые числа/обязанности и ложные
  внешние действия отвергаются. Arithmetic helper — точный ограниченный BigDecimal,
  вызывается для всех явно распознаваемых выражений; unsupported CALCULATE уточняется.
- Новая `EvidenceTranslation` — отдельный local4B перевод только необходимых foreign
  paragraph groups, native KZ originals сохраняются. JSON/IDs/numbers/refs проверки
  НЕ доказывают переводную корректность. generator_rag добавляет маленькие conservative
  critical-term guards и независимо оценивает actual KZ/ordinary dev outputs.
- Native worker v2 SHA `90c09a1bcac6ed3eea74f9cf4baf8a8da7ceb7847e3d69334242bc866acf86f6`:
  отдельная exact-first-system translation grammar, selector grammar сохранена.
  `quality-v2/native-translation-v2/control-model-v2`, manifest `7ead4569…`;
  31 parser checks + real10/10 transport PASS, GPU lease освобождена. Tiny translation
  смыслово ошибочна (добавлен архив) — transport PASS не превращать в semantic PASS.
  portable готовит НОВЫЕ full6811 `runtime-dev-foundation-n2`/`runtime-dev-ordinary-n2`.
  Старые C6CE/control-v1/dev/frozen/root файлы не изменены.
- Open development28 =14 RU/KZ intents/5families, SHA `465c4dcb…`; пересечение со ВСЕМИ
  actual E5 train17families/18docs нулевое. DevRU14-v4 foundation conservative source7/14,
  user-ready5/14, misses/search/overclosure подтверждены независимым review.
  ordinary-v4b имеет тот же input, но ранний foundation-v4 classpath не был frozen;
  НЕ называть это чистым причинным эффектом. Новый v6 должен сохранить единый snapshot.
- source_portability сейчас исправляет DOCX numbered/heading boundaries и lexical
  reranking, который выбросил wage gold с dense/merged rank1. Actual CPU raw64 сохранён
  `retrieval/candidate-pool-ordinary-v1/pools.jsonl` SHA `2bb6620f…`, full7/0generation,
  peak1.29GB, CUDA DLL не загружались. После source-only14 replay сообщит final sourceSHA.
- Весь49reserve не был вне старого E5train: overlap2 docs8550/8561 относятся именно
  к prior-exposed guard5; все44eligible и test120/task10 НЕ пересекаются с old FT.
  Новый final owner подтвердил metadata, sealed вопросы root не открывал.
- report_finish создал actual classical pilot30cases: rules macroF1 .8167,
  LogisticRegression .9152 (pairedCI включает0), GradientBoosting .8143; оба exportREJECT,
  research weights сохранены. Новое приложение не использует эти неподтверждённые модели.
- report_finish готовит open actiondev14 (12actions+2math,7RU7KZ) вне sealed; utility
  будет проверяться реально доfreeze. Подробные actual-training/engineering приложения
  на новом Desktop сохраняются, старый frozen отчёт/ZIP неизменны.
- Portable сделал UI SourcePresentation18checksPASS: doc partial ≠ неполный ответ,
  fragment needs_review и отсутствиеhumanreview отдельно; details/cards сохранены.
  Delta installer transaction и outer wrapper реализуются, ещё НЕ accepted/published.
- Полноценный native app ещё НЕ пересобран; безопасный build handoff в
  `quality-v2/native/NATIVE-BUILD-HANDOFF.md`. Передbuild сохранить11 mutable outputs
  переименованием из-за HARDLINKS oldEXE/JAR; не вызывать defaultstatic/nativeLibraries.
- Новый dev guard `quality-v2/host/run_dev.py` теперь копирует hostclasses+source,
  сохраняет неизменные aliases full7JAR/libs и inventory SHA на каждый новый run.
  Прошлые v1-v4 без snapshot не выдавать за воспроизводимые causal comparisons.
- Обнаружен bundled Python с pypdf/pdfplumber/pypdfium2:
  `C:/Users/Oskar/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`.
  PDF skill прочитан, root сообщил пользователю. Только read-only source page audit,
  никаких промежуточных APP screenshots. generator получил exactskill/runtime paths.
- Next: дождаться source/helper/native2 stage handoffs; compile full7 overlays, v6 paired
  base/ordinary dev28 + open action14 с ОДНИМ неизменным snapshot; исправлять до готовности.
  Затем full native build, новое freeze, independently sealed120+task10, final GUI,
  accepted runtime promotion, NEW GitHub release+Markdown report. Не завершать цель раньше.

## Активная работа: исправление качества v2, прежний релиз не является завершением новой цели

Новая инструкция пользователя: исправить неполные/ошибочные ответы, пройти приёмку,
затем обновить GitHub и Markdown-отчёт; работать автономно. Последнее уточнение:
полезный прямой ответ или готовый проект решения должен идти первым, существенные
условия и исключения сохранять, служебные предупреждения не заменяют ответ.

- Ветка `codex/quality-grounding-v2-20261024`, исходный HEAD `4cb5a34`.
  Старые release/tag, Desktop-отчёт и frozen результаты сохранять неизменными.
- Работа только с полными семью модулями, без module-removal, матриц состава,
  отдельных проверок графа и Process Monitor. Модели/исходники/checkpoints не удалять.
- Новые результаты: `src/build/research-20261024/quality-v2/`; новый отчёт:
  `C:/Users/Oskar/Desktop/Исследование_локального_ИИ_2026-10-24_v2/`.
- Root владеет `EvidenceAnswer.java`, `TaskSolution.java`, новым маршрутом
  `AssistantModule.java` и `EvidenceAnswerTest.java`. Модель выбирает ID оснований
  JSON-грамматикой, приложение проверяет ID и показывает целые канонические положения.
  Одно совпадение текста/наличие ссылки не считается смысловой проверкой.
- generator_rag: Intent + stream gate (497/4168 focused assertions PASS), затем
  независимый read-only review root route. source_portability: поиск/полные bundles,
  development-пакеты и TRAIN-only признаки; report_finish: обучение/методы/новый отчёт
  и сопоставление rules/logistic regression/shallow gradient boosting.
- portable_release_finish: новый native worker SHA `c6ceef41…`, 22 модельно-независимые
  проверки PASS. Разрешён реальный native control на единственной GPU-аренде.
  Root/другие GPU не запускать до подтверждения освобождения `gpu.lock`.
- Полный новый control model `quality-v2/native/control-model-v1`, manifest
  `39a73942…`; full-seven dev runtime `quality-v2/runtime-dev-foundation` содержит
  ещё прежний EXE/JAR, новые Java классы запускать отдельным полным host classpath.
  Ничего из dev runtime не объявлять финальной GUI-сборкой.
- final_evaluation независимо хранит новый sealed120 (80RU/40KZ), reserved49families,
  54docs; root/implementers не читать случаи/эталоны до freeze. Public criteria v3
  `evaluation/public/preregistered-criteria-v3.json`, SHA `38af69ad…`:
  primary >=80%, прирост >=10п.п. с парным CI>0, без роста критических ошибок,
  relevance precision >=80%, answer-first и реальный полезный action result.
- Старые final/challenge/demo теперь только diagnostics. Новый final не открыт.
  После исправления по новому final нужен ещё один отложенный тест.
- Без GPU новых inference пока не было; native controls запускаются первыми.
  Далее full-seven dev queries: организация, процедура, готовый черновик/план.
  Root helpers уже PASS18; интеграция Intent/Search/main ещё не собрана.
- Диск после документированного перемещения только дублирующихся distribution cache:
  C около31.0GB, D6.26GB. Минимум20GiB на C. Все прежние веса/обучение сохранены.
- Компоненты frozen/root жёстко связаны с копиями: EXE/worker.json обновлять только
  независимым новым файлом/атомарной заменой, никогда записью поверх hardlink.
- Не объявлять новую цель выполненной, пока нет честной приёмки и новой публикации.

Далее — история предыдущей завершённой публикации; её «ЗАВЕРШЕНИЕ» относится только
к старому экспериментальному релизу, а не к активной задаче исправления качества.

## Возобновление после перезапуска — актуальный статус

### ЗАВЕРШЕНИЕ: комплект и отчёт опубликованы, публичная доставка проверена

- Публичный экспериментальный prerelease:
  https://github.com/MarkZamore/Mini-AI/releases/tag/v2026.10.24-local-ai .
  Release ID422387026, draft=false, prerelease=true. Source/tag commit
  `cbd15cd875bfe3444cb2522df8af89cc19e4105b`; последующие изменения только MD.
- Все 11 assets (9 приложения + ZIP отчёта и его sidecar) по API совпали
  по имени, размеру и SHA. `release-preparation/github-published-foundation.json`.
  Независимые public checks: все 16 HTTP Range, точные pinned URL и полный SHA
  setup/SHA256SUMS PASS. Receipt `github-public-check-v1/verification.json`,
  SHA `b7669013f0652181dc4b1413eeef69740855d76f4a0cdc85ffec84dfca812a6c`.
  Отчёт ZIP и его sidecar полностью скачаны без авторизации и SHA-проверены:
  `github-public-report-check-v1.json`. Большие части повторно целиком не скачивались.
- Markdown-отчёт на Desktop завершён: 2321 слово, 256 относительных ссылок PASS,
  пять baseline и пять actual foundation GUI ответов дословно, 50 карточек,
  18 финальных raw +18 точных crops. ZIP 29160553 B, 670 entries CRC+SHA PASS;
  SHA `4a7f3c97353d632f964a45b21374754a7d6e8c04ac9dceb53cd1064e423a5369`.
  Локальный ZIP `report-release-v1/Mini-AI-research.zip`, опубликован тем же файлом.
  Веса и канонический корпус в отчёте не дублировались. Замороженные Desktop/ZIP
  не менять; поздние квитанции публикации сохраняются отдельно.
- ROOT EXE160219…, manifest91eef…, SQLite58d6…, userinstructions958d… после GUI
  и CPU совпадают с утверждёнными SHA. Все Mini-AI/workers завершены, lease снят.
  Старые модели/checkpoints, исходные документы и обе резервные конфигурации целы.
- Исследовательский план завершён с отрицательным результатом приёмки качества:
  FT поиска улучшил retrieval, но не доказал прирост качества полных ответов.
  Поставлен frozen foundation operational fallback. Нормативная надёжность
  НЕ доказана. Все пять GUI имеют warning неполноты; название организации
  потеряно в первом, в пятом оборван пункт. Не исправлялись по открытому final.
  Нет проверки человеком, настоящего звонка Teams и запуска на целевом ноутбуке.
  Это ограничения результата, а не выполненные проверки.

Ниже — сохранённая история. При противоречии действует этот верхний блок.

### Актуально: foundation уже в корне, архив проверен, завершается демонстрация и публикация

- ROOT успешно переведён на `mini-ai-20261024-4b-base-e5-foundation-fallback-v1`.
  `root-promotion-foundation-v1/acceptance.json`: 335 файлов проверены, прежние
  шесть единиц сохранены в backup; модельный manifest `91eef09c28743ebae6a8ab251e841cc0f8b8446681e3831de77ed879457fe82a`.
  Это operational fallback, нормативная надёжность не доказана. Production после
  original FREEZE не изменялся и по ошибкам финального теста не настраивался.
- Новый комплект `D:/Mini-AI-release-20261024-foundation-v1` готов. Deep verify:
  все 6811 файлов и девять publication assets PASS. Публиковать ТОЛЬКО его
  `publication-assets.json`, SHA `549511b24c076eae23289dc373e820e86dc907d32121a10bfd614e305c8636b0`.
  Setup SHA `ba2c95809c0ffe5a73de1985ec6e32252023a5dbc126d4938dcb7b954d0ea2f8`.
- generator_rag завершил все пять настоящих final foundation GUI-диалогов,
  50 карточек, 18 raw и 18 точных crops. Desktop final/demo-01..05.json готовы;
  все пять показывают предупреждение неполноты. Ошибки не скрывать. Все приложения
  закрыты; guardian `final-gui-foundation-session-v1` session80714 exit0,
  `complete.json` подтверждает lease_released, gpu.lock отсутствует.
- source_portability завершил ровно один точный foundation CPU full-seven запрос,
  exit0. First nonempty 174.8650608 s, full 203.5852019 s, 2199 chars/10 sources,
  качество unknown и warning неполноты. Peak tree RSS 5121536000 B, минимум
  свободной RAM 16913657856 B. Live ONNX+ggml-cpu, без CUDA. Teams idle, звонка нет.
  Worker profile SHA3217e5… и frozen control manifest91eef… unchanged, workers=[],
  lease отсутствует. Артефакты `cpu-foundation-probe-v1/run`. Не повторять.
  Старые 174/224 секунды относятся к отклонённому trained-кандидату.
- Markdown-отчёт на настоящем Desktop: 2284 слова, девять приложений, 183 ссылки
  проверены. report_finish добавит пять GUI, точный CPU и публикацию, затем ZIP.
- Исходники уже опубликованы: commit `cbd15cd875bfe3444cb2522df8af89cc19e4105b`,
  main и codex branch, annotated tag `v2026.10.24-local-ai`. Draft prerelease
  ID `422387026` (пока не public), setup уже uploaded/digest PASS. Root uploader
  exec session `39944`, журнал `release-preparation/github-upload-foundation.jsonl`;
  два параллельных streaming uploads, без clobber. Дождаться всех девяти assets,
  сверить API SHA/size, затем report ZIP и только после этого publish.
  API lookup draft через releases/422387026, не releases/tags (возвращает 404).
  19 устаревших runtime файлов model убраны только из Git index (git rm --cached);
  реальные файлы и резервные копии сохранены. Проверка source commit/tree,
  frozen source/JAR, секретов и quality claims: source-publication-audit-v1.json PASS.
- C свободно около 23.6 GB, D около 1.57 GB. Никаких новых больших копий.
  Четыре части старого trained-архива сохранены на C, остальные на D согласно
  `retained-trained-archive-v1/retained-layout.json`; ни один байт не потерян.

Ниже сохранена история. При противоречии действует этот самый верхний блок.

### САМАЯ НОВАЯ ТОЧКА: кандидат отклонён, поставка возвращается к frozen foundation control

- Все12/12 финальных jobs и combined scoring завершены, runtimeerrors0.
  `final-evaluation-run-v1/challenge-reviewed-all-v1/metrics.json` SHA
  `08a1dda1d0dc38ea5b320efb48628f96add0b4cd79f9086dfe07ef013544273b`.
  TrainedE5 улучшила actual-context Recall56→95%, но полные ответы6/28=6/28,
  парный95%CI[-20;+25]п.п.; critical4(trained) против3(foundation), unknown19/30
  у обоих. Уlexical0/28,critical10; boundedу всех3/4correct+1unknown.
- По ПРЕДЗАДАННОМУ правилу accept/reject root отклонил trainedE5 для поставки
  и вернулся к ЗАРАНЕЕ FROZEN role`untrained`, без нового тюнинга. Это operational
  fallback, НЕ доказанно надёжный нормативный помощник. final_evaluation независимо
  подтвердил методологическую допустимость и точные metadata. Все исходные scores,
  веса, индекс, FREEZE1fb75… сохранены, не переписывать историю.
- Актуальное решение `deployment-decision-v2.json`, SHA
  `7d2a6e2a7f8cf88c0fbbda551199cad750a23a87bba58ba6b64280fc225965dc`.
  V1b26b… сохранён. Поставляемый ID
  `mini-ai-20261024-4b-base-e5-foundation-fallback-v1`, runtime
  `final-control-untrained-v1`, manifest
  `91eef09c28743ebae6a8ab251e841cc0f8b8446681e3831de77ed879457fe82a`.
  EXE160219… и corpus58d6… те же. V2 связывает originalFREEZE, полныйstage6811
  и postruncoveragecompanion. Frozeninventory6789+22longdocs; все22actualSHA
  независимо совпали с originalsourceSHA из frozen document JSON.
- ROOT ПОКА ЕЩЁ TRAINED candidate после `root-promotion-final-v1` (335files/6units),
  manifest02538…; oldoriginal EXE/model/native/licenses/CRT целиком сохранены
  в backup, SQLite и instructions неизменны. Foundationpromotion ещё НЕ выполнен.
  `root-promotion-final-v1/acceptance.json` подтверждает первое promotion,
  но НЕ качество. Следующий output `root-promotion-foundation-v1`.
- Trained native GUI: первый вопрос дал неполный ответ без названия организации.
  Его2rawJPEG+JSON перенесены из Desktop в `rejected-trained-gui-v1` с manifest,
  НЕ выдавать их за shipped final и не включать промежуточные кадры в отчёт.
  Тот app/workers закрыты; guardian session29689 завершён exit0, lease снят.
- Source_portability владеет расширением `promote_root.py --decision` (старый
  freeze-режим сохраняется) и rootREADME. Final_evaluation уже независимо прочёл
  новый helper и decisionv2: PASS; audit `release-guard-audit/decision-v2-audit.json`.
  Ждать helper-owner ready/dry-run и применить root после проверки отсутствияPID/lease.
- Portable_release_finish готовит `portable.py build --decision` с настоящим
  linkedFREEZE/keyuntrained/fullstage gate, без fakefreeze; installertransport
  не меняется. Новый output `D:/Mini-AI-release-20261024-foundation-v1`.
  Предыдущий trained archive12.205GB уже build/deepverifyPASS на Dfinal-v1, но НЕ
  опубликован. Для места owner сохраняет4точныхparts001..004 (7.516GB) на Cresearch
  `retained-trained-archive-v1` черезcopy→SHAverify→unlinkТОЛЬКОсвоих4archivefiles,
  с layout/journal. Остальныеparts/metadata остаютсяD. Все weights/trainingresults
  сохраняются. ПрогнозC23.65GB>20GiB/D13.77GB>newpayload+1GiB, guards обязательны.
- **Единственный Windows UI owner теперь generator_rag**, root Sky не использует.
  Он читает `HANDOFF-final-gui-foundation.md` и ЖДЁТ отдельного сигнала root:
  сначала foundationpromotion, затем новый guardian `final-gui-foundation-session-v1`,
  после этого пять новых настоящих ROOT GUI диалогов/10sourcecards/raw/crops.
  Сейчас пять foundationfinal ещё НЕ сделаны. Baseline7raw+7cropped сохранены.
- Report_finish обновляет MD под foundation fallback; основное содержание около
 2254слов, mainsemanticunknown, 6/28autoнеaccuracy, критические ошибки явно описаны.
  Дляпоставки latency=untrained TTFTmedian/p95 4.183/8.260s, full6.009/10.965s.
  Это frozenfull7JavaRAG, не nativeGUIlatency. Teams signed-in idle, звонка нет.
- GitHub/commit/tag/push ЕЩЁ НЕ сделаны. Originmain c92dbff; branch
  codex/local-ai-research-20261024, gh authMarkZamore уже имеется. Tag
  v2026.10.24-local-ai свободен. Root должен закончить fiveGUI/reportMD+bundle,
  sourcecommit/push и новыйfoundationrelease/digests/publicURLs; не публиковать
  старыеcandidateassets по ошибке. Release notes ещё требуют foundation wording.

Ниже — более ранние сохранённые стадии; при противоречии действует блок выше.

### Позднейшая контрольная точка: финальный отложенный тест выполняется

- Конфигурация `mini-ai-20261024-4b-base-e5-ordinary-final-v1` зафиксирована:
  `final-evaluation-preparation/FREEZE-v1.json`, SHA
  `1fb75aab2a04e42d655ee272d0a29e3d380ec7e422400d0d5fda5ae275e619d9`.
  Spec SHA `ee936043128d50ebfd0fa017e7e0f4b287fff5a5248a5cb676d3a9785cecf0fb`.
- Sealed100 ОТКРЫТ после фиксации, 2026-10-23T22:01:22Z; SHA совпал с
  публичным манифестом. Процессом владеет агент `final_evaluation`, exec session
  29724, lease `frozen-final-evaluation`. Последний сигнал: final/answer100
  завершён100/100 exit0; final/challenge-full-rag завершён30/30 exit0,
  bounded4 выполняется. Ошибок и перезапусков нет. Не запускать дубликат.
- Три полных конфигурации final/untrained/lexical проходят answer100, search100,
  challenge30 full-RAG и challenge4 bounded. Выход `final-evaluation-run-v1`.
  После открытия теста не менять production-код, веса, индекс, prompt и пороги.
- Teams авторизован и работает idle, реального звонка нет. Последние samples:
  VRAM used4636MiB/free11413MiB, availableRAM16.6GB; два worker-процесса —
  generationCUDA и evidenceCPU, а не два GPU-генератора.
- Публикация/продвижение root EXE/model, финальные пять настоящих GUI-примеров
  и окончательный Markdown пока впереди. source_portability обновляет README,
  report_finish — Desktop отчёт/reproduce, portable_release_finish готовит план
  архива, но ждёт разрешения начать большой IO после всех оценочных прогонов.
- Уточнение: большой IO отложен до конца ВСЕХ12jobs, чтобы не искажать парные
  показатели задержки. Portable план/hash/archive всё ещё не запускались.
  `src/release-research/promote_root.py` готов и проверен dryrun335files/6units,
  но rootpromotion НЕ выполнен. Старые root EXE/model всё ещё сохранены на месте.
- Source-based automatic review finalchallenge: generator_rag первые17cases
  исходного порядкаrevision2, source_portability последние17. Выходы ожидаются
  `final-challenge-reviews/final-part1.jsonl` и `final-part2.jsonl`. Не считать
  самостоятельной человеческой проверкой; scorer/revieworigin фиксированы.
- Root создал7croppedPNG только из ранее сохранённых baselineJPEG, без новых
  кадров/resize/маскировки. `screenshots/baseline-crop-manifest.json` в Desktop
  фиксирует SHA и rectangle(10,30,750,970), raw сохранены.

- Final native EXE SHA160219d9351faad98b57c2c26394f13a4ea3e79af9172d794b1502e390a0dccd,
  314363904bytes;226+policy/streamPASS. Полный runtime `final-runtime-v1`:6811files,
  12,202,736,500logicalbytes, hardlinks. Native CLI status/DB/model, реальный
  E5search и CUDAgeneration,8fragment/resolvecalls4locator kinds PASS.
- Реальный SWT/native GUI отменён при уже видимом generated answer; следующий
  запрос в ТОМ ЖЕ окне завершён, карточка цитаты открыта. Артефакт
  `final-native-smoke/gui-lifecycle.json`; intermediate PNG не сохранялись.
  GUI appPID19384 завершён, его GPU lease освобождён.
- Teams запущен и уже авторизован; idle, **звонка нет**. Оставлен работать.
- Реальный context overflow→next10PASS. CPUfullRAG1 с Teams: first174.326s,
  full224.006s, ответ помечен неполным. Функциональный fallback, **не проходит
  latency15/60**, не semanticPASS. PeakRSS4.77GiB, minfreeRAM15.04GiB;
  фактические CPUworker modules безCUDA проверены.
- ВROOTуже применён корпус:5292missing hardlinks добавлены,1184existingmatching
  сохранены, SQLiteSHA прежний. Журнал `corpus-portability-v2/root-deployment-apply.jsonl`.
  ROOT EXE/model всё ещё старые; их promotion/rollback впереди.
- Дополнительный challenge утверждён ДОответов: `challenge-v1/revision-2`,
  manifestSHA98b2b251fa1fe2f5641914a6c937ec60c1bf8c899ed4f2bc5e8b88b306bac1e3;
  casesSHA0ef1162e28b8191b8fa74ace249bafe57087440c8f2b7b2f499501cb8bfdd6fc.
  34cases=28fullRAGanswer+2clarify+4boundedinsufficient;24RU/10KZ,
  11DOCX/9families внеtrain. Independent automatic pre-answer audit PASS,
  `source-audit/delta-review.json` SHAa2ccac0d3accb04a9ddc14a5e56749017fe77b33a12ec5243de177a6033eae8d.
- Полные control runtimes готовы: `final-control-untrained-v1` manifest91eef09c…
  и `final-control-lexical-v1` manifest0ef05475…, все6811assets/7modules.
  Neighborcontext dev20:7expanded changed; mandatorycoverage20/20 у обоих,
  gain0, это только retrieval/evidencecontrol, не answerquality.
- Root ЯВНО разрешил final_evaluation агенту зафиксировать spec/FREEZE и затем
  открытьsealed100. Фактические freeze и запуск подтверждены выше. Проверять
  живой агент/новые файлы перед любым своим run; не дублировать evaluation.

Работа возобновлена 24 октября 2026; доступны шесть субагентов (включая дочерних).
Старый текст ниже описывает сохранённую паузу и не является текущим статусом.

- Полный Java состав пересобран. Paired RAG20 `generator/dev-rag20/paired-v1`
  и `paired-v2` завершены: одинаковые упорядоченные источники 20/20, все семь
  модулей, неизвестных ссылок и runtime ошибок нет. V2 literal base6/20, A10/20,
  CI прироста включает0; A зацикливается на нерелевантной фразе. Выбран base4B;
  E5 ordinary seed1 + RRF остаётся поиском. Это не доказательство широкой
  семантической приёмки; реальные ошибки сроков/адресатов сохранены в review.
- Исправлены SOURCE и регрессии: punctuation/paragraph loss после цитат,
  явные вопросы «в документе…» теперь нормативные; при найденных источниках,
  но отсутствии цитированного ответа не сообщается, будто источников нет.
  Последняя правка включается в финальную native сборку агентом full_build.
- Корпус `portable-corpus-v2`: 6476 files, SHA inventory
  be47e3cb1b329e801984de2299c3d2e704ff7b6ad7295c2eb09eebebae0737ec.
  Реальный DocumentsService/full7 проверил 599 originals,599 JSON,5328 chains,
  179686 source_refs. Root19missing originals и artifacts ещё НЕ развёрнуты.
  `src/release-research/corpus/deploy.py` dry-run:1184 existing matching,
  5292 missing; journalled hardlink-only apply пока не запускался.
- `src/release-research/portable.py` stage/plan/build/verify --deep,
  standalone PS5.1 installer + CPU/CUDA profiles готовы; транспортные small
  fixture проверки PASS. Больших архивов/upload ещё нет; default install CPU.
- `src/research/final_evaluation.py` готов, sealed-final всё ещё НЕ открыт.
  Дополнительный разнообразный challenge готовится report_finish и детьми
  до freeze; его использовать только для оценки после freeze, не для подбора.
- Desktop Markdown draft2057 слов +6 приложений, все57 ссылок существуют.
  Final/Teams/UI/публикация ещё pending. Native app/root model ещё прежние.

При следующем продолжении сначала проверить live agents/их процессы и новые
handoff/result files: активный build или RAG нельзя дублировать по старому тексту.

Сохранено 24 октября 2026 года по прямому запросу пользователя о паузе.
Все три субагента остановлены после записи handoff. Обучение завершено,
активных исследовательских Python/Java/worker-процессов и GPU lease нет.
Проект НЕ объявлен завершённым. Корневое приложение и рабочая папка model
ещё исходные: новые кандидаты пока находятся в исследовательской папке.

## Где продолжать

- Проект: `C:/Users/Oskar/Documents/Mini-AI`.
- Ветка: `codex/local-ai-research-20261024`; исходный HEAD `c92dbff84a5ff850c9e324b2228fcec00b6beea2`.
- Исследование: `src/build/research-20261024` (игнорируется Git).
- Отчёт и иллюстрации: `C:/Users/Oskar/Desktop/Исследование_локального_ИИ_2026-10-24`.
- Черновой `Отчёт.md`: около 1950 слов, ещё НЕ итоговый.
- Подробные handoff: `HANDOFF-audit_research.md`, `HANDOFF-model_options.md`,
  `HANDOFF-audit_architecture.md` в папке исследования.
- Снимок изменённых/новых исходников, Git diff и manifest состояния:
  `src/build/research-20261024/restart-checkpoint-20261024/`.

## Неизменяемые требования

Продолжить пользовательский план автономного улучшения поиска, локального
обучения и проверяемых ответов. Сохранять все существенные условия, сроки,
исключения, адресатов и действия. Сокращать служебный шум, не содержание.
Соблюдать AGENTS.md: только полный состав из семи модулей; никаких
module-removal, матриц состава, отдельных graph tests и Process Monitor.
Не удалять документы пользователя, прежние модели, checkpoints и результаты.
Не проходить авторизацию, не менять настройки безопасности, не требовать UAC.

Скриншоты нужны ТОЛЬКО исходного и окончательного приложения; промежуточные
эксперименты сохранять текстом/метриками. В отчёте сравнить время ответа
до/после; разделять startup, настоящий первый текст, полный ответ и длину.
Не указывать длительность проекта или обучения. Итоговый отчёт только Markdown,
1800–2400 слов основного текста плюс приложения, рассказ и FAQ.

Независимого человека-проверяющего нет. Автоматическая проверка, существующая
цитата и буквальное совпадение не доказывают смысловую полноту нормативного
ответа. Не объявлять неизвестную оценку успешной.

## Что уже сделано

1. Сохранены исходный EXE, manifest, патч и пользовательские исходные правки.
   Создана рабочая ветка; исходные docs/db не изменялись.
2. Получены пять НАСТОЯЩИХ baseline ответов в интерфейсе, JSON и raw-снимки:
   Desktop `baseline/demo-01.json` … `demo-05.json`, `screenshots/raw/before-*`.
   Это демонстрация, не слепой тест и не чистый замер задержки (были фоновые работы).
3. Teams завершён на время обучения. После обучения ещё не запускался для
   окончательной демонстрации. Настоящий звонок не проверен.
4. Реализованы EvidenceModelServiceV1, единый native worker с CUDA generation
   и CPU ONNX embeddings/reranking, manifest v2 с SHA и строгими DLL-проверками.
   v1/generation JSONLv1 сохранены. Python конечному приложению не нужен.
5. Добавлены hybrid RRF, сохранение канонических привязок, соседних условий,
   corpus identity/version и запрет продолжать историю после смены базы.
6. Добавлены нормативный режим, строгие потоковые ссылки, уведомление о неполном
   ответе, адаптивный объём вместо обязательных 2–4 предложений.
7. UI показывает короткие ссылки и компактные предупреждения; полные исходные
   идентификаторы/предупреждения сохранены в карточке источника/истории.
8. Действительно обучены два полных E5 FT, повтор ordinary с другим seed,
   два 4B QLoRA и partial FT последних четырёх блоков 0.8B. Старые адаптеры не
   использовались. Checkpoints содержат optimizer/scheduler/RNG.
9. Проверены native E5 tokenizer100 и численный embedding parity; BGE tokenizer100
   и logit parity при длине256 также PASS, max logit error0.
10. Целевой native lifecycle PASS: отмена → следующий запрос; malformed JSON во
    время BUSY → controlled exit2. Найденный lifetime bug исправлен.
11. Generation220, prompt projection22, policy/stream/citations, manifest10,
    full-composition identity15, UI68 и реальный persistent Windows Job11 — PASS.
    См. handoff: последние Java изменения ещё включить в окончательную сборку.

## Зафиксированные данные и решения

Канонический корпус: 599 документов, 106139 фрагментов, 217 partial.
SHA SQLite: `58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a`.
Проверенный native pool: 564 фрагмента, 40 документов/36 семейств.
Train91 намерение (61RU/30KZ,17семейств), development120;
selection80 содержит 72 вопроса одного семейства, поэтому выводы узкие.
Final100 (67RU/33KZ,8семейств), из них30 transfer. 96 случаев — извлечение
положений,4 — явные условия; это не полноценный широкий semantic benchmark.

**SEALED FINAL НЕ ОТКРЫВАЛСЯ.** Папка `data/sealed-final`, SHA набора
`75ba09584976432e67d8ef8f61f4811de1abae6f00909b83a01546efb60b3f75`.
Не читать его до фиксации весов, индекса, промпта, кода и порогов.
Пять демонстрационных тем исключены из обучения/подбора; документы в индексе.

**Принят как перспективный компонент:** ordinary E5 seed1 + hybrid RRF.
На одинаковом native pipeline untrained→trained: Recall@10 .400→.975,
nDCG .275762→.872222. CI95 для ΔnDCG [.059176;.640450] положителен;
для ΔRecall [0;.618182] включает0. Второй seed повторил направление эффекта.
Это вклад весов в поиск на узком наборе, НЕ общая точность ответов.

**Отклонены:** BGE ordering8/12/24 (нет устойчивого выигрыша), bounded-context
логистический классификатор (теряет покрытие), BGE hard gate top3 с заранее
фиксированными 0/−2.5. Native gate140: selection74 answer/5clarify/1insufficient,
то есть потеря7.5п.п. > бюджета3п.п.; calibration19/20, gate20/20;
stress19insufficient+1nohits, но семантические отрицательные метки UNKNOWN.
Пороги после результата не меняли. Рабочий кандидат БЕЗ BGE ordering И hard gate.
Код optional возможностей/исследовательские веса сохранены.

## Генераторы и кандидаты

Исходная 4B Q6_K SHA:
`3acaea00a98af4e8cb7195259d7dd903b0cc749dff61a8cf47274c9a91ed02f6`.
Локальная A Q6_K: `generator/A/matched-baseline-Q6_K.gguf`, SHA
`f31c4e102d1a691bc91e5e390c99ef3d07600ac948f3329b05a7569eaef8d369`.
Одинаковы container/header/tokenizer и426tensor inventory; 178frozen tensors
byte-identical, заменены248learned Q6 matrices,744native block samples сверены.
НЕ ПРИМЕНЯТЬ неудачный `.partial.gguf` или `--finalize-existing` к нему:
он содержит foundation без LoRA и был отклонён guard.

4B A — кандидат полного RAG, ещё не окончательный победитель. A2/stress улучшили
буквальное воспроизведение/ссылки, но15/16 вопросов одного семейства; семантическая
полнота неизвестна. 9B общего преимущества не показала. B только немного лучше
NLL; её merge не запускать без основания. 0.8B literal coverage не выросла.

Full-RAG20 зафиксирован ДО ответов:
`generator/dev-rag20/input.jsonl`, SHA
`4ae917959e2bd4518bd983c5d71310622cababbeae33dacbdca9275a8a48c1c1`.
20 случаев =8minority +6RU/6KZdominant, итого14RU/6KZ и5семейств.
**Реальные парные RAG20 ещё НЕ запускались.**

Свежие model packages успешно подготовлены (по48hashedfiles):

- `candidate-hybrid-base/model`, manifest SHA `02538af6b25d3450a38686f2db51a5f40773cb90a498d0445174ce3a5fd0073a`.
- `candidate-hybrid-A/model`, manifest SHA `67acb0b840990f1f8941212b41101e406e7272f64117e773ebb0b767589d023a`.

Оба: ordinary E5, БЕЗ BGE, ctx16384, CUDA budget8GiB/reserve3GiB,
Job max8GiB/minfreeRAM9GiB. **В корнях этих runtime ещё нужно подготовить
native/ и readonly db/ (как в rag-runtime-v1), затем проверить readiness.**
Не путать с отклонённым `candidate-base`, где BGE hard gate для исследования.

Финальный worker source:
`gpu-worker/persistent-package/model`, EXE SHA
`60b57752154f6f1b8104c1d8880c9bc6551dda072db59b5dcce6ff6bd84e4ff8`.
Включены Haswell/x64, точные llama b11669/CUDA12.4 DLLs и лицензии.
Последний SHA менял только evidence maxLength256; generation Job11 относится
generation-identical471294…; benchmark hashes сохранены отдельно.

## Следующие действия после возобновления

1. Прочесть три handoff. Проверить фактический лимит субагентов; распределить
   независимые задачи до6, без одновременных правок одного файла. GPU тяжёлые
   запуски только последовательно через `gpu.lock`.
2. Скомпилировать текущий полный Java состав (последние WorkerManifest CPU-DLL
   compatibility и InferenceModule pipe-close fixes ещё включить в build).
3. Подготовить runtime roots candidate-hybrid-base/A, запустить paired RAG20
   через `src/research/java/ai/enterprise/research/RagEvaluation.java`, проверить
   точное ordered equality фактических контекстов, ошибки, ссылки, атомы,
   полноту/unknown. Scorer в `src/generator-research/dev_rag.py`.
4. Выбрать 4B base/A по реальным результатам. Сохранить проигравшие. Если
   преимущество генераторного FT не доказано — оставить исходный генератор.
5. Исправить ПОЛНОТУ переносимого комплекта источников до публикации.
   ВАЖНАЯ незавершённая находка: обычный pathlib увидел19missing originals/599
   и отсутствие корневого db/artifacts; clean-install имеет artifacts, но27
   original paths тоже не найдены. Это только наблюдение путей, ещё проверить
   Unicode/long paths/архивы/реальный resolver. Не менять канонические данные
   наугад и не публиковать текущий root вслепую.
6. Зафиксировать finalist (коды/веса/index/prompt/параметры/SHA), только затем
   открыть final100 и выполнить честную автоматическую оценку с ограничениями.
   Для F нужны контрольные замены обученных моделей исходными; не подгонять
   решение по финальному тесту. После исправлений по final нужен новый тест.
7. Окончательно собрать полный native app, проверить реальные цитаты/отмену/
   следующий запрос/контекст/источники. Teams можно снова запустить: обучение
   закончено. Измерить RAM/VRAM, latency с Teams без фоновых тяжёлых задач;
   отсутствие настоящего звонка прямо отметить, если он не проведён.
8. Пять final вопросов в новом диалоге каждый, настоящий максимизированный UI,
   тот же масштаб; сохранить полные ответы/источники/raw и читаемые crops.
   Не переделывать/не дорисовывать baseline. Длинные ответы — последовательные кадры.
9. Завершить Desktop MD/приложения/графики/воспроизведение. Только фактические
   результаты, без длительности проекта/обучения.
10. Обновить root комплект с возможностью rollback; сохранить исходные
    пользовательские Markdown/UI правки, `MODEL-INTERIM.md` и `model/instructions.md`.
11. Пользователь ранее явно просил публикацию обновлённого приложения и нужных
    файлов на GitHub. `gh auth status` успешно: MarkZamore, origin
    `https://github.com/MarkZamore/Mini-AI.git`. Публикации/commit/push ещё НЕ было.
    Подготовить конкретный проверенный portable release существующим pipeline,
    не включать devtools/старые веса/внутренние логи. Аудит release в handoff
    architecture. На C оставлять≥20GiB; архивы при необходимости на D.

## Среда и команды

Python: `src/build/model-preparation/venv/Scripts/python.exe` (использовать `-X utf8`).
Java25: `src/build/tools/graalvm-community-openjdk-25.0.2+10.1/bin/`.
CLI harness classpath:
`src/build/research-20261024/java;src/build/classes/java/main;src/build/resources/main;src/build/runtime-libs/*`.
Harness modes: `root input.jsonl output.jsonl search|answer|dense [limit]`;
результатный файл должен быть новым. App modules все7 через GeneratedCatalog.
`src/build.ps1 -Action build -Profile native` — полный build;
НЕ запускать общий test/run.ps1 с запрещёнными graph/module-removal проверками.

Для Windows UI читать skill computer-use и применять только node_repl + @oai/sky;
старых переменных sky/window после перезапуска нет. Сначала inventory, затем
один ввод → fresh state. Из-за occlusion/анимации проверять настоящий кадр Mini-AI
перед сохранением; несколько ранних неправильных снимков были заменены корректными.

На момент паузы C свободно около28.9GiB, D17.2GiB. Большие веса на Desktop
не дублировались. Конфигурация Codex уже содержит лимит6; текущая сессия
фактически позволяла только3субагента. После нового запуска проверить реальную
доступность, не обещать обход лимита среды.

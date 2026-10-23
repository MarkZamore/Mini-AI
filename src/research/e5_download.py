"""Resolve a public model revision once; subsequent downloads are pinned."""
import argparse, hashlib, json, pathlib
from huggingface_hub import HfApi, snapshot_download

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('src/build/research-20261024/models/e5/base'));a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);lock=a.out.parent/'foundation-lock.json'
    if lock.exists(): info=json.loads(lock.read_text())
    else:
        model=HfApi().model_info('intfloat/multilingual-e5-small',revision='614241f622f53c4eeff9890bdc4f31cfecc418b3')
        info={'repo_id':model.id,'revision':model.sha,'source':'https://huggingface.co/intfloat/multilingual-e5-small','license':'mit','weights_updated':False};lock.write_text(json.dumps(info,indent=2)+'\n')
    snapshot_download(info['repo_id'],revision=info['revision'],local_dir=a.out,allow_patterns=['config.json','model.safetensors','tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model','README.md'])
    files={}
    for f in a.out.iterdir():
        if f.is_file():
            with f.open('rb') as h: files[f.name]={'size':f.stat().st_size,'sha256':hashlib.file_digest(h,'sha256').hexdigest()}
    info['files']=files;lock.write_text(json.dumps(info,indent=2)+'\n');print(json.dumps(info,indent=2))
if __name__=='__main__':main()

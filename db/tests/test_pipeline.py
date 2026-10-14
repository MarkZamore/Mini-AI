import copy, json, os, sys, tempfile, unittest, zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from common import DB_ROOT, atomic_json, sha256, validate_document
from formats import Context, extract_docx, extract_xlsx, NS
from search import Database, build_index

def document(source):
    digest=sha256(source)
    return {'schema_version':'1.0','document_id':'satbayev:document:1','source_version_sha256':digest,'title':'Проверка документа',
        'source':{'path':'source.docx','name':'source.docx','format':'docx','size':source.stat().st_size,'sha256':digest,'urls':['https://example.test/document/1'],'sections':['Test'],'manifest_path':'test'},
        'artifacts':[],'blocks':[],'assets':[],'references':[],
        'extraction':{'status':'complete','fingerprint':'0'*64,'converted_at':'2026-10-14T00:00:00Z','tools':{},'settings':{},'coverage':{},'warnings':[],'errors':[]}}

class PipelineTests(unittest.TestCase):
    def test_tesseract_literal_quotes_are_not_csv_quoting(self):
        from repair_ocr import parse_tsv
        data=b'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t95\t"University\n5\t1\t1\t1\t1\t1\t15\t0\t10\t10\t96\tdocument"\n'
        words,_=parse_tsv(data);self.assertEqual([w['text'] for w in words],['"University','document"'])
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='satbayev_tests_'); self.root=Path(self.temp.name)
        (self.root/'schema').mkdir(); (self.root/'schema'/'document.schema.json').write_bytes((DB_ROOT/'schema'/'document.schema.json').read_bytes())
        (self.root/'documents').mkdir()
    def tearDown(self): self.temp.cleanup()
    def word(self,body):
        path=self.root/'source.docx'
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('word/document.xml','<w:document xmlns:w="'+NS['w']+'"><w:body>'+body+'</w:body></w:document>')
        return path
    def test_word_merged_cells_and_nested_tables(self):
        body='''<w:p><w:r><w:t>Документы университета</w:t></w:r></w:p><w:tbl>
        <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Объединенная ячейка</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge/></w:tcPr><w:p/><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Вложенная</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl>'''
        path=self.word(body); d=document(path); extract_docx(Context(d,self.root),path); validate_document(d,self.root)
        tables=[b for b in d['blocks'] if b['kind']=='table']
        self.assertEqual(len(tables),2); cell=tables[0]['payload']['cells'][0]
        self.assertEqual((cell['rowspan'],cell['colspan']),(2,2)); self.assertEqual(tables[1]['parent_id'],tables[0]['id'])
        self.assertEqual(len(cell['source_refs']),2)
    def test_alternate_content_not_duplicated(self):
        path=self.root/'source.docx'
        xml='<w:document xmlns:w="'+NS['w']+'" xmlns:mc="'+NS['mc']+'"><w:body><mc:AlternateContent><mc:Choice Requires="w"><w:p><w:r><w:t>Выбранная ветка</w:t></w:r></w:p></mc:Choice><mc:Fallback><w:p><w:r><w:t>Повтор</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent></w:body></w:document>'
        with zipfile.ZipFile(path,'w') as z:z.writestr('word/document.xml',xml)
        d=document(path);extract_docx(Context(d,self.root),path)
        self.assertEqual([b['text'] for b in d['blocks']],['Выбранная ветка'])
    def test_index_rebuild_queries_and_stale_original(self):
        path=self.word('<w:p><w:r><w:t>Исследование документов университета</w:t></w:r></w:p>')
        d=document(path);extract_docx(Context(d,self.root),path);atomic_json(self.root/'documents'/'1.json',d)
        build_index(self.root)
        with Database(self.root) as db:
            hits=db.search('документ университет');self.assertTrue(hits)
            first=hits[0];self.assertEqual(db.get_fragment(first['fragment_id'])['text'],first['text'])
            ref=first['source_refs'][0];self.assertTrue(db.resolve_source(ref)['verified'])
            # User text is compiled to quoted FTS terms; punctuation cannot become SQL/MATCH syntax.
            self.assertIsInstance(db.search('" OR *'),list)
        before=[b['id'] for b in d['blocks']];build_index(self.root)
        with Database(self.root) as db:self.assertEqual(db.connection.execute('select count(*) from chunks').fetchone()[0],1)
        path.write_bytes(b'changed')
        with Database(self.root) as db:
            with self.assertRaises(ValueError):db.resolve_source(ref)
    def test_sparse_excel_keeps_formula_cache_and_drawings(self):
        path=self.root/'source.xlsx'; rel='http://schemas.openxmlformats.org/package/2006/relationships'
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('xl/workbook.xml','<workbook xmlns="'+NS['s']+'" xmlns:r="'+NS['r']+'"><sheets><sheet name="Лист" sheetId="1" r:id="r1"/></sheets></workbook>')
            z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="'+rel+'"><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>')
            z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="'+NS['s']+'" xmlns:r="'+NS['r']+'"><dimension ref="A1:XFD1048576"/><sheetData><row r="1"><c r="A1"><f>1+1</f><v>2</v></c><c r="XFD1" s="1"/></row></sheetData><mergeCells><mergeCell ref="A1:B2"/></mergeCells><drawing r:id="d1"/></worksheet>')
            z.writestr('xl/worksheets/_rels/sheet1.xml.rels','<Relationships xmlns="'+rel+'"><Relationship Id="d1" Target="../drawings/drawing1.xml"/></Relationships>')
            z.writestr('xl/drawings/drawing1.xml','<xdr:wsDr xmlns:xdr="'+NS['xdr']+'" xmlns:a="'+NS['a']+'"><xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:row>1</xdr:row></xdr:from><xdr:sp><xdr:nvSpPr><xdr:cNvPr id="1" name="shape"/></xdr:nvSpPr><xdr:txBody><a:p><a:r><a:t>Текст схемы</a:t></a:r></a:p></xdr:txBody></xdr:sp></xdr:twoCellAnchor></xdr:wsDr>')
        d=document(path);extract_xlsx(Context(d,self.root),path);validate_document(d,self.root)
        table=d['blocks'][0];self.assertEqual(len(table['payload']['cells']),1)
        cell=table['payload']['cells'][0];self.assertEqual(cell['formula'],'=1+1');self.assertEqual(cell['cached_value'],2)
        self.assertEqual((cell['rowspan'],cell['colspan']),(2,2));self.assertEqual(d['blocks'][1]['text'],'Текст схемы')
    def test_atomic_publication_rejects_nonfinite_and_invalid_locator(self):
        path=self.word('<w:p><w:r><w:t>Текст</w:t></w:r></w:p>'); d=document(path);extract_docx(Context(d,self.root),path)
        target=self.root/'result.json';atomic_json(target,d);before=target.read_bytes()
        with self.assertRaises(ValueError):atomic_json(target,{'bad':float('nan')})
        self.assertEqual(before,target.read_bytes());bad=copy.deepcopy(d);bad['blocks'][0]['source_refs'][0]['source_sha256']='1'*64
        with self.assertRaises(ValueError):validate_document(bad,self.root)
        self.assertFalse(list(self.root.glob('*.tmp')))

if __name__=='__main__':unittest.main()

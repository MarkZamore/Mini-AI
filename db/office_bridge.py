"""Runs under the isolated LibreOffice Python/UNO runtime."""
import sys,time,os
import uno
from com.sun.star.beans import PropertyValue

def property(name,value):
    p=PropertyValue(); p.Name=name; p.Value=value; return p

def main():
    pipe,source,target,fmt=sys.argv[1:]
    local=uno.getComponentContext(); resolver=local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver',local)
    context=None
    for _ in range(900):
        try: context=resolver.resolve('uno:pipe,name='+pipe+';urp;StarOffice.ComponentContext'); break
        except Exception: time.sleep(.1)
    if context is None: raise RuntimeError('Private LibreOffice UNO connection timed out')
    desktop=context.ServiceManager.createInstanceWithContext('com.sun.star.frame.Desktop',context)
    document=desktop.loadComponentFromURL(uno.systemPathToFileUrl(source),'_blank',0,
        (property('Hidden',True),property('ReadOnly',True),property('UpdateDocMode',0),property('MacroExecutionMode',4)))
    if document is None: raise RuntimeError('LibreOffice could not load source')
    try:
        if hasattr(document,'enableAutomaticCalculation'): document.enableAutomaticCalculation(False)
        filter_name={'docx':'Office Open XML Text','xlsx':'Calc MS Excel 2007 XML','pdf':'writer_pdf_Export'}[fmt]
        document.storeToURL(uno.systemPathToFileUrl(target),(property('FilterName',filter_name),property('Overwrite',True)))
    finally:
        document.close(True)
        if os.environ.get('SATBAYEV_OFFICE_KEEPALIVE')!='1':desktop.terminate()

if __name__=='__main__': main()

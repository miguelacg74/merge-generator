package com.generator.mergedml;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;

import oracle.ide.editor.EditorManager;

/** Salida del script generado: portapapeles o worksheet nuevo. */
public final class MergeOutput {

    private MergeOutput() {
    }

    public static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    /**
     * Guarda el script en un .sql temporal y lo abre en una hoja de trabajo
     * nueva (SQL Developer asocia los .sql al worksheet).
     *
     * @return el fichero creado
     * @throws Exception si no se puede escribir o abrir el fichero
     */
    public static File openInNewWorksheet(String text) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File file = new File(System.getProperty("java.io.tmpdir"), "merge_" + stamp + ".sql");
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
        EditorManager.getEditorManager().openDefaultEditorInFrame(file.toURI().toURL());
        return file;
    }
}

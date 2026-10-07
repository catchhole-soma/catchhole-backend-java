// Run with hwplib 1.1.11 and hwpxlib 1.0.9 on the classpath (test fixture generation only).
// All manuscript sentences below are synthetic, authored for CatchHole GH224.
import java.nio.file.*;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

public class CreateHangulFixtures {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (int number = 1; number <= 2; number++) {
            var file = BlankFileMaker.make();
            file.getBodyText().getSectionList().getFirst().getParagraph(0).getText()
                    .addString("제 " + number + "화 " + (number == 1 ? "새벽의 편지\n서윤은 성문 앞에서 편지를 읽었다." : "다시 만난 길\n도윤은 오래된 약속을 떠올렸다."));
            HWPWriter.toFile(file, output.resolve("episode-" + number + ".hwp").toString());
        }
        var file = BlankFileMaker.make();
        file.getBodyText().getSectionList().getFirst().getParagraph(0).getText()
                .addString("제 1화 새벽의 편지\n서윤은 성문 앞에서 편지를 읽었다.");
        var next = BlankFileMaker.make().getBodyText().getSectionList().getFirst();
        next.getParagraph(0).getText().addString("제 2화 다시 만난 길\n도윤은 오래된 약속을 떠올렸다.");
        file.getBodyText().getSectionList().add(next);
        HWPWriter.toFile(file, output.resolve("two-sections.hwp").toString());
        writeTableFixture(output);
        kr.dogfoot.hwpxlib.writer.HWPXWriter.toFilepath(
                kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker.make(), output.resolve("blank.hwpx").toString());
    }

    private static void writeTableFixture(Path output) throws Exception {
        var file = BlankFileMaker.make();
        var paragraph = file.getBodyText().getSectionList().getFirst().getParagraph(0);
        paragraph.getText().addString("관계표");
        paragraph.getText().addExtendCharForTable();
        var table = (ControlTable) paragraph.addNewControl(ControlType.Table);
        table.getHeader().setWidth(30000);
        table.getHeader().setHeight(16000);
        table.getTable().setColumnCount(3);
        table.getTable().setBorderFillId(1);
        var row = table.addNewRow();
        addTableCell(row, 0, 0, 1, "이름");
        addTableCell(row, 1, 0, 1, "관계");
        addTableCell(row, 2, 0, 1, "소속");
        row = table.addNewRow();
        addTableCell(row, 0, 1, 1, "서윤");
        addTableCell(row, 1, 1, 1, "자매");
        addTableCell(row, 2, 1, 1, "북쪽");
        row = table.addNewRow();
        addTableCell(row, 0, 2, 1, "도윤");
        addTableCell(row, 1, 2, 2, "아군");
        row = table.addNewRow();
        addTableCell(row, 0, 3, 3, "합류");

        for (boolean compressed : new boolean[]{true, false}) {
            file.getFileHeader().setCompressed(compressed);
            var path = output.resolve(compressed ? "table-rows.hwp" : "table-rows-uncompressed.hwp");
            HWPWriter.toFile(file, path.toString());
            var reloaded = HWPReader.fromFile(path.toString());
            var reloadedTable = (ControlTable) reloaded.getBodyText().getSectionList().getFirst().getParagraph(0)
                    .getControlList().stream().filter(ControlTable.class::isInstance).findFirst().orElseThrow();
            if (reloadedTable.getRowList().size() != 4
                    || reloadedTable.getRowList().get(2).getCellList().get(1).getListHeader().getColSpan() != 2
                    || reloadedTable.getRowList().get(3).getCellList().getFirst().getListHeader().getRowIndex() != 3) {
                throw new IllegalStateException("표 fixture의 행/병합 정보가 보존되지 않았습니다.");
            }
        }
    }

    private static void addTableCell(Row row, int column, int rowIndex, int columnSpan, String text) throws Exception {
        var cell = row.addNewCell();
        var header = cell.getListHeader();
        header.setColIndex(column);
        header.setRowIndex(rowIndex);
        header.setColSpan(columnSpan);
        header.setRowSpan(1);
        header.setWidth(10000 * columnSpan);
        header.setHeight(4000);
        header.setTextWidth(10000 * columnSpan);
        header.setBorderFillId(1);
        var paragraph = cell.getParagraphList().addNewParagraph();
        paragraph.createText();
        paragraph.getText().addString(text);
        paragraph.createCharShape();
        paragraph.getCharShape().addParaCharShape(0, 0);
    }
}

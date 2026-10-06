// Run with hwplib 1.1.11 and hwpxlib 1.0.9 on the classpath (test fixture generation only).
// All manuscript sentences below are synthetic, authored for CatchHole GH224.
import java.nio.file.*;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
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
        kr.dogfoot.hwpxlib.writer.HWPXWriter.toFilepath(
                kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker.make(), output.resolve("blank.hwpx").toString());
    }
}

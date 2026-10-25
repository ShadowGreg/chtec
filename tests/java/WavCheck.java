import ru.chtec.core.Wav;
import java.io.*;
public class WavCheck {
    public static void main(String[] a) throws Exception {
        File raw = new File(a[2] + ".pcm");
        int rate = Wav.info(new File(a[0])).rate; long len = 0;
        try (OutputStream o = new FileOutputStream(raw)) {
            len += Wav.appendPcm(new File(a[0]), rate, o); len += Wav.appendSilence(500, rate, o);
            len += Wav.appendPcm(new File(a[1]), rate, o);
        }
        try (OutputStream o = new FileOutputStream(a[2]); InputStream i = new FileInputStream(raw)) {
            Wav.writeHeader(o, rate, len); i.transferTo(o);
        }
    }
}

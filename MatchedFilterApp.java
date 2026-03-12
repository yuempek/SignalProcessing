import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║         Chirp Radar — PRI Döngüsü + Waterfall Grayscale Display      ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  1. Chirp sinyali (g) oluştur                                        ║
 * ║  2. PRI ile sürekli: çal → kaydet → matched filter                   ║
 * ║  3. Sonuçları dairesel (circular) matrise yaz                        ║
 * ║  4. Kayan waterfall panelinde gri ton görselleştir                   ║
 * ║  5. Mouse tooltip: mesafe (ses hızı = 30000 cm/s)                    ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
public class MatchedFilterApp {

    // ── Parametreler ──────────────────────────────────────────────────────────
    static final float  FS           = 44100f*4f;   // Örnekleme frekansı (Hz)
    static final double T_CHIRP      = 0.1;      // Chirp süresi (saniye)
    static final double T_PRI        = 0.3;      // PRI süresi (saniye) — chirp + yankı payı
    static final double F0           = 1000.0;    // Başlangıç frekansı (Hz)
    static final double F1           = 15000.0;   // Bitiş frekansı (Hz)
    static final int    N_CHIRP      = (int)(FS * T_CHIRP);
    static final int    N_PRI_REC    = (int)(FS * T_PRI);   // kayıt örnek sayısı

    // Ses hızı (cm/s) — mesafe hesabı için
    static final double SOUND_SPEED_CM_PER_S = 30000.0;

    // Circular matrix boyutları
    static final int MATRIX_ROWS    = 200;   // kaç PRI saklanacak (dairesel)
    static final int MATRIX_COLS    = 1024*8;  // her satırdaki görüntü sütunu

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        // 1. Chirp üret
        double[] g = generateChirp(N_CHIRP, FS, F0, F1, T_CHIRP);
        System.out.printf("Chirp oluşturuldu: %d örnek (%.2f s)%n", g.length, T_CHIRP);

        // 2. Circular matrix: her satır bir PRI'ın matched filter sonucu
        float[][] circMatrix = new float[MATRIX_ROWS][MATRIX_COLS];
        int[] writeHead = {0};   // dairesel yazma başlığı

        // 3. GUI başlat
        WaterfallPanel panel = new WaterfallPanel(circMatrix, writeHead);
        JFrame frame = buildFrame(panel);

        // 4. PRI döngüsü (ayrı thread)
        AtomicBoolean running = new AtomicBoolean(true);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                running.set(false);
            }
        });

        Thread priThread = new Thread(() -> priLoop(g, circMatrix, writeHead, panel, running));
        priThread.setDaemon(true);
        priThread.start();

        frame.setVisible(true);
    }

    // Hedef sütun: maxIdx bu indekse hizalanacak
    static final int PEAK_TARGET_COL = MATRIX_COLS/3;

    // =========================================================================
    // PRI Döngüsü — sürekli çal → kaydet → filtrele → hizala → matrise yaz
    // =========================================================================
    static void priLoop(double[] g, float[][] matrix, int[] head,
                        WaterfallPanel panel, AtomicBoolean running) {
    	
    	double[] gListened = new double[g.length];
    	
        int pri = 0;
        while (running.get()) {
            try {
                long t0 = System.currentTimeMillis();

                // Çal + Kaydet
                double[] f = playAndRecord(g, FS, T_CHIRP);

                // Matched filter
                double[] mf = matchedFilter(f, g);

                // maxIdx bul
                // DENENDi : chirp'un yakalandigi yerden itibaren kayit chirp olarak degerlendirildi
                int maxIdx = 0;
                for (int i = 1; i < mf.length; i++) 
                	if (mf[i] > mf[maxIdx]) maxIdx = i;

                for (int i = 1; i < gListened.length; i++) gListened[i] = 0.0;
                
                System.arraycopy(f, maxIdx, gListened, 0, Math.min(gListened.length, f.length - 1 - maxIdx));
                
                // for (int i = 0; i < gListened.length; i++) gListened[i] *= 1000.0;

                // mf = matchedFilter(f, gListened);

                // Downsample → MATRIX_COLS nokta
                float[] row = downsample(mf, MATRIX_COLS);

                // maxIdx bul
                int maxRowIdx = 0;
                for (int i = 1; i < row.length; i++)
                    if (row[i] > row[maxRowIdx]) maxRowIdx = i;

                // maxIdx'i PEAK_TARGET_COL'a getirecek kadar kaydır
                // shift > 0 → sağa kaydır (sola padding), shift < 0 → sola kaydır (sağa padding)
                int shift = PEAK_TARGET_COL - maxRowIdx;
                row = shiftWithPadding(row, shift);

                // Circular matrix'e yaz
                synchronized (matrix) {
                    matrix[head[0]] = row;
                    head[0] = (head[0] + 1) % MATRIX_ROWS;
                }

                panel.repaint();

                long elapsed = System.currentTimeMillis() - t0;
                pri++;
                System.out.printf("PRI #%d tamamlandı — maxIdx=%d  shift=%d  elapsed=%d ms%n",
                                  pri, maxRowIdx, shift, elapsed);

                // PRI süresine tamamla (kalan zamanı bekle)
                long wait = (long)(T_PRI * 1000) - elapsed;
                if (wait > 0) Thread.sleep(wait);

            } catch (Exception e) {
                System.err.println("PRI hata: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Diziyi 'shift' kadar kaydır, boş kalan kısımları 0 ile doldur
    //   shift > 0 → içerik sağa kayar, sol taraf sıfırlanır
    //   shift < 0 → içerik sola kayar, sağ taraf sıfırlanır
    // =========================================================================
    static float[] shiftWithPadding(float[] src, int shift) {
        int len = src.length;
        float[] dst = new float[len]; // default 0.0f (zero padding)
        if (shift >= 0) {
            int copyLen = len - shift;
            if (copyLen > 0)
                System.arraycopy(src, 0, dst, shift, copyLen);
        } else {
            // Kaynak [-shift .. len-1] → hedef [0 .. len+shift-1]
            int copyLen = len + shift; // shift negatif olduğundan toplama
            if (copyLen > 0)
                System.arraycopy(src, -shift, dst, 0, copyLen);
        }
        return dst;
    }

    // =========================================================================
    // Waterfall Görselleştirme Paneli — Mouse Tooltip ile
    // =========================================================================
    static class WaterfallPanel extends JPanel {
        private final float[][] matrix;
        private final int[]     head;
        private final Font      monoFont = new Font("Monospaced", Font.BOLD, 11);

        // Mouse pozisyonu (panel koordinatları)
        private int mouseX = -1, mouseY = -1;
        private boolean mouseInside = false;

        // Kenar boşlukları — paintComponent ile aynı değerler
        private int marginLeft = 60, marginRight = 20, marginTop = 40, marginBottom = 40;

        WaterfallPanel(float[][] matrix, int[] head) {
            this.matrix = matrix;
            this.head   = head;
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(1100, 700));

            // Mouse hareketi dinleyici
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    mouseX = e.getX();
                    mouseY = e.getY();
                    mouseInside = true;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    mouseX = e.getX();
                    mouseY = e.getY();
                    mouseInside = true;
                    repaint();
                }
            });

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    mouseInside = false;
                    repaint();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    mouseInside = true;
                }
            });
        }

        /**
         * Verilen panel X koordinatından mesafeyi cm olarak hesaplar.
         * X ekseni = gecikme zamanı (0 → T_PRI saniye)
         * Mesafe = (gecikme / 2) * ses_hızı  (gidiş-dönüş)
         */
        private double xToDistanceCm(int px) {
            int imgW = getWidth() - marginLeft - marginRight;
            // Görüntü içindeki piksel oranı [0..1]
            double ratio = (double)(px - marginLeft) / imgW;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            // Gecikme süresi (saniye)
            double delaySec = ratio * T_PRI;
            // Mesafe = (tek yön) = (gidiş-dönüş süresi / 2) * ses hızı
            double distanceCm = (delaySec / 2.0) * SOUND_SPEED_CM_PER_S;
            return distanceCm;
        }

        /**
         * Verilen panel Y koordinatından PRI indeksini hesaplar.
         * En üst = en yeni PRI (#0), en alt = en eski PRI (#MATRIX_ROWS-1)
         */
        private int yToPriIndex(int py) {
            int imgH = getHeight() - marginTop - marginBottom;
            double ratio = (double)(py - marginTop) / imgH;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            return (int)(ratio * (MATRIX_ROWS - 1));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g2 = (Graphics2D) g0;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int panelW = getWidth();
            int panelH = getHeight();

            int imgW = panelW - marginLeft - marginRight;
            int imgH = panelH - marginTop - marginBottom;

            // Circular matrix → BufferedImage (grayscale)
            BufferedImage img = new BufferedImage(MATRIX_COLS, MATRIX_ROWS,
                                                  BufferedImage.TYPE_INT_RGB);
            synchronized (matrix) {
                int latestRow = (head[0] - 1 + MATRIX_ROWS) % MATRIX_ROWS;
                for (int row = 0; row < MATRIX_ROWS; row++) {
                    // En yeni satır → en üstte (waterfall etkisi)
                    int matRow = (latestRow - row + MATRIX_ROWS) % MATRIX_ROWS;
                    float[] data = matrix[matRow];
                    for (int col = 0; col < MATRIX_COLS; col++) {
                        float v = Math.min(1f, Math.max(0f, data[col]));
                        // Gri ton: phosphor-green efekti (radar estetiği)
                        int gray  = (int)(v * 255);
                        int r     = (int)(v * 40);
                        int green = gray;
                        int b     = (int)(v * 20);
                        img.setRGB(col, row, (r << 16) | (green << 8) | b);
                    }
                }
            }

            // Görüntüyü panel alanına çiz
            g2.drawImage(img, marginLeft, marginTop, imgW, imgH, null);

            // Çerçeve
            g2.setColor(new Color(0, 180, 80));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(marginLeft, marginTop, imgW, imgH);

            // Başlık
            g2.setFont(new Font("Monospaced", Font.BOLD, 14));
            g2.setColor(new Color(0, 230, 100));
            g2.drawString("CHIRP RADAR — MATCHED FILTER WATERFALL", marginLeft, marginTop - 10);

            // X ekseni: zaman etiketi
            g2.setFont(monoFont);
            g2.setColor(new Color(0, 160, 70));
            int nTicks = 5;
            for (int i = 0; i <= nTicks; i++) {
                double tSec = T_PRI * i / nTicks;
                int px = marginLeft + imgW * i / nTicks;
                g2.drawLine(px, marginTop + imgH, px, marginTop + imgH + 5);
                g2.drawString(String.format("%.1fs", tSec), px - 12, marginTop + imgH + 18);
            }
            g2.drawString("Gecikme (Range)", marginLeft + imgW / 2 - 40, marginTop + imgH + 35);

            // Y ekseni: PRI etiketi
            g2.rotate(-Math.PI / 2);
            g2.drawString("PRI (Yeni → Eski)", -(marginTop + imgH / 2 + 50), marginLeft - 35);
            g2.rotate(Math.PI / 2);

            // Renk çubuğu (legend)
            int cbX = marginLeft + imgW + 5, cbY = marginTop, cbH = imgH, cbW = 14;
            for (int row = 0; row < cbH; row++) {
                float v = 1f - (float) row / cbH;
                int gray = (int)(v * 255);
                g2.setColor(new Color((int)(v*40), gray, (int)(v*20)));
                g2.fillRect(cbX, cbY + row, cbW, 1);
            }
            g2.setColor(new Color(0, 160, 70));
            g2.drawRect(cbX, cbY, cbW, cbH);
            g2.drawString("1.0", cbX, cbY + 10);
            g2.drawString("0.0", cbX, cbY + cbH);

            // ── Mouse Tooltip ────────────────────────────────────────────────
            if (mouseInside && mouseX >= marginLeft && mouseX <= marginLeft + imgW
                            && mouseY >= marginTop  && mouseY <= marginTop  + imgH) {

                double distanceCm = xToDistanceCm(mouseX);
                int    priIdx     = yToPriIndex(mouseY);

                // Çapraz saç teli (crosshair)
                g2.setColor(new Color(255, 255, 0, 120));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                             1.0f, new float[]{4f, 4f}, 0f));
                g2.drawLine(mouseX, marginTop, mouseX, marginTop + imgH);
                g2.drawLine(marginLeft, mouseY, marginLeft + imgW, mouseY);

                // Tooltip metni
                String line1 = String.format("Mesafe : %.1f cm", distanceCm);
                String line2 = String.format("PRI    : #%d", priIdx);

                Font   ttFont    = new Font("Monospaced", Font.BOLD, 12);
                g2.setFont(ttFont);
                FontMetrics fm   = g2.getFontMetrics(ttFont);
                int tw1 = fm.stringWidth(line1);
                int tw2 = fm.stringWidth(line2);
                int ttW = Math.max(tw1, tw2) + 18;
                int ttH = fm.getHeight() * 2 + 14;

                // Tooltip konumu: mouse'un sağına ve altına, ekran sınırı aşarsa sola/yukarı kaydır
                int ttX = mouseX + 14;
                int ttY = mouseY + 14;
                if (ttX + ttW > panelW - 10) ttX = mouseX - ttW - 10;
                if (ttY + ttH > panelH - 10) ttY = mouseY - ttH - 10;

                // Arka plan
                g2.setColor(new Color(0, 0, 0, 200));
                g2.fillRoundRect(ttX - 2, ttY - 2, ttW + 4, ttH + 4, 8, 8);

                // Kenarlık
                g2.setStroke(new BasicStroke(1.0f));
                g2.setColor(new Color(0, 220, 80));
                g2.drawRoundRect(ttX - 2, ttY - 2, ttW + 4, ttH + 4, 8, 8);

                // Metin
                g2.setColor(new Color(180, 255, 100));
                g2.drawString(line1, ttX + 6, ttY + fm.getAscent() + 4);
                g2.setColor(new Color(100, 200, 80));
                g2.drawString(line2, ttX + 6, ttY + fm.getAscent() + fm.getHeight() + 6);
            }
        }
    }

    // =========================================================================
    // JFrame kurulumu
    // =========================================================================
    static JFrame buildFrame(WaterfallPanel panel) {
        JFrame frame = new JFrame("Chirp Radar — Waterfall Display");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(Color.BLACK);
        frame.getContentPane().add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        return frame;
    }

    // =========================================================================
    // Matched filter sonucunu MATRIX_COLS boyutuna düşür (max-pooling)
    // =========================================================================
    static float[] downsample(double[] data, int targetLen) {
        float[] out = new float[targetLen];
        double ratio = (double) data.length / targetLen;

        // Max-pooling: her hedef hucre icin bloktaki en buyuk dB degerini al
        for (int i = 0; i < targetLen; i++) {
            int start = (int)(i * ratio);
            int end   = Math.max(start + 1, (int)((i + 1) * ratio));
            double mx = Double.NEGATIVE_INFINITY;
            
            for (int j = start; j < end && j < data.length; j++)
                if (data[j] > mx) mx = data[j];
            
            out[i] = (float) mx;
        }
		
		// dB degerlerini [0..1] araligina normalize et (min-max)
        // log olceginde min genellikle cok negatif, maks 0 e yakin
        normalize(out);
        
        return out;
    }

    
    static float[] normalize(float[] out) {
    	float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (float v : out) { if (v < minV) minV = v; if (v > maxV) maxV = v; }
        float range = maxV - minV;
        if (range < 1e-6f) range = 1f;
        for (int i = 0; i < out.length; i++) {
            out[i] = (out[i] - minV) / range;
        }
    	return out;
    }
    
    // =========================================================================
    // Chirp üretimi — doğrusal frekans süpürme
    //   g[n] = sin(2π * (f0 + k/2 * t) * t),   k = (f1-f0)/T
    // =========================================================================
    static double[] generateChirp(int n, float fs, double f0, double f1, double tTotal) {
        double[] g = new double[n];
        double k = (f1 - f0) / tTotal;
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            g[i] = Math.sin(2.0 * Math.PI * (f0 + 0.5 * k * t) * t);
            
            double w = 0.8;
            double a = -0.5; //fix
            double b = 0.1;  //fix
            double x = i/(n-1.0);
            g[i] *= Math.pow(Math.exp(-Math.pow((x+a)/b/w, 2)), 0.2);
        }
        return g;
    }

    // =========================================================================
    // Hoparlörden çal + mikrofon kaydı (eş zamanlı)
    // =========================================================================
    static double[] playAndRecord(double[] g, float fs, double tChirp) throws Exception {
        AudioFormat format = new AudioFormat(fs, 16, 1, true, false);

        // Mikrofon
        DataLine.Info recInfo = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(recInfo))
            throw new RuntimeException("Mikrofon desteklenmiyor.");
        TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(recInfo);
        mic.open(format);

        // Hoparlör
        DataLine.Info playInfo = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(playInfo))
            throw new RuntimeException("Hoparlör desteklenmiyor.");
        SourceDataLine speaker = (SourceDataLine) AudioSystem.getLine(playInfo);
        speaker.open(format);

        byte[] chirpBytes = doubleToPCM16(g);
        byte[] recBuffer  = new byte[N_PRI_REC * 2]; // T_PRI kadar kayıt

        mic.start();
        speaker.start();

        // Kayıt thread'i
        Thread recThread = new Thread(() -> {
            int offset = 0, remaining = recBuffer.length;
            while (remaining > 0) {
                int read = mic.read(recBuffer, offset, remaining);
                if (read > 0) { offset += read; remaining -= read; }
            }
        });
        recThread.start();

        // Chirp'i çal
        speaker.write(chirpBytes, 0, chirpBytes.length);
        speaker.drain();

        recThread.join();
        mic.stop();   mic.close();
        speaker.stop(); speaker.close();

        return pcm16ToDouble(recBuffer);
    }

    // =========================================================================
    // Matched Filter
    //   result = |ifft(FFT(f) · conj(FFT(g)))| / sqrt(energyF · energyG)
    //
    //   Düzeltmeler (orijinal koddan):
    //   - Enerji FFT'den SONRA hesaplanıyor (Parseval doğru uygulaması)
    //   - Geri oynatma kaldırıldı
    // =========================================================================
    static double[] matchedFilter2(double[] f, double[] g) {
        int n       = f.length;
        int fftSize = nextPow2(n);

        double[] fReal = Arrays.copyOf(f, fftSize);
        double[] fImag = new double[fftSize];
        double[] gReal = Arrays.copyOf(g, fftSize);
        double[] gImag = new double[fftSize];


        // gain
//        for (int i = 0; i < fftSize; i++) {
//        	double t = i/FS * 5; 
//        	double gain = Math.exp(t);
//        	fReal[i] = fReal[i]*gain;
//        }

        fft(fReal, fImag, false);
        fft(gReal, gImag, false);
       
        double energyF = 0, energyG = 0;
        for (int i = 0; i < fftSize; i++) {
            energyF += fReal[i]*fReal[i] + fImag[i]*fImag[i];
            energyG += gReal[i]*gReal[i] + gImag[i]*gImag[i];
        }
         
        double denom = Math.sqrt(energyF * energyG);
        if (denom == 0) denom = 1;


        
        double[] prodReal = new double[fftSize];
        double[] prodImag = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            double a = fReal[i], b = fImag[i];
            double c = gReal[i], d = gImag[i];
            prodReal[i] =  a*c + b*d;   // Re{F · G*}
            prodImag[i] =  b*c - a*d;   // Im{F · G*}
        }

        fft(prodReal, prodImag, true);

        double[] linear = new double[n];
        for (int i = 0; i < n; i++) {
            linear[i] = Math.sqrt(prodReal[i]*prodReal[i] + prodImag[i]*prodImag[i]) / denom;
        }

        final double EPSILON = 1e-9;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = 20.0 * Math.log10(linear[i] + EPSILON);
        }
        return result=linear;
    }

    
    static double[] matchedFilter(double[] f, double[] g) {
        int n = f.length; // Sinyal uzunluğu
        int m = g.length; // Chirp uzunluğu
        
        // FFT boyutu: Konvolüsyon teoremi için 2^k ve N+M-1'den büyük olmalı
        int fftSize = nextPow2(n + m);

        // --- 1. PAYDA: g sinyalinin sabit enerjisi ---
        double gEnergy = 0;
        for (double val : g) gEnergy += val * val;

        
        // --- 2. PAY: Çapraz İlinti (FFT ile) ---
        double[] f_re = Arrays.copyOf(f, fftSize); //pad(f, fftSize);
        double[] f_im = new double[fftSize];
        //double[] g_re = pad(reverse(g), fftSize); // Correlation için g ters çevrilir
        double[] g_re = Arrays.copyOf(g, fftSize); 
        double[] g_im = new double[fftSize];

        for (int i = 0; i < FS * 0.05; i++) {
        	f_re[i] = 0;
        }

        for (int i = 0; i < fftSize; i++) {
	    	double t = (i)/FS; 
	    	double gain = Math.exp(t);
	    	// gain = Math.pow(t, 2);
	    	
	    	double power = 0;
			gain = Math.pow((Math.max(t, 0)/0.1)*power+1, 2);
	    	
			f_re[i] = f_re[i]*gain;
	    }
        
        fft(f_re, f_im, false);
        fft(g_re, g_im, false);
        
        for (int i = 0; i < fftSize; i++) {
        	// reverse 
        	g_im[i] = -g_im[i];
        }
        
        // Frekans uzayında çarpım (Complex multiplication)
        double[] corr_re = new double[fftSize];
        double[] corr_im = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            corr_re[i] = f_re[i] * g_re[i] - f_im[i] * g_im[i];
            corr_im[i] = f_re[i] * g_im[i] + f_im[i] * g_re[i];
        }
        
        fft(corr_re, corr_im, true); // Pay hazır (Numerators)

        // --- 3. PAYDA: f sinyalinin hareketli enerjisi ---
        // f(a)^2 dizisini oluştur
        double[] f_sq_re = new double[fftSize];
        for (int i = 0; i < n; i++) f_sq_re[i] = f[i] * f[i];
        double[] f_sq_im = new double[fftSize];

        // Birlerle dolu pencere (n uzunluğunda)
        double[] win_re = new double[fftSize];
        for (int i = 0; i < m; i++) win_re[i] = 1.0;
        double[] win_im = new double[fftSize];

        fft(f_sq_re, f_sq_im, false);
        fft(win_re, win_im, false);

        // Frekans uzayında çarpım
        double[] movingEnergy_re = new double[fftSize];
        double[] movingEnergy_im = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            movingEnergy_re[i] = f_sq_re[i] * win_re[i] - f_sq_im[i] * win_im[i];
            movingEnergy_im[i] = f_sq_re[i] * win_im[i] + f_sq_im[i] * win_re[i];
        }
        
        fft(movingEnergy_re, movingEnergy_im, true); // Payda terimi hazır

        // --- 4. BİRLEŞTİRME: p(x) ---
        double[] p = new double[n]; // Sonuç dizisi
        for (int i = 0; i < n; i++) {
            double num = Math.pow(corr_re[i], 2);
            double den = movingEnergy_re[i] * gEnergy;
            
            // Bölme hatasını (0) engellemek için küçük bir epsilon eklenebilir
            p[i] = (den > 1e-12) ? num / den : 0;
        }
        
        final double EPSILON = 1e-9;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = 20*Math.log10(p[i]+EPSILON+0.001);
        }
        
        return result;
    }

    // =========================================================================
    // Cooley-Tukey FFT (in-place, boyut 2^k)
    // =========================================================================
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        double sign = inverse ? 1.0 : -1.0;
        for (int len = 2; len <= n; len <<= 1) {
            double ang = sign * 2 * Math.PI / len;
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i+j],           uIm = im[i+j];
                    double vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    double vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i+j]       = uRe + vRe;  im[i+j]       = uIm + vIm;
                    re[i+j+len/2] = uRe - vRe;  im[i+j+len/2] = uIm - vIm;
                    double nr = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = nr;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    static int nextPow2(int n) { int p = 1; while (p < n) p <<= 1; return p; }

    static byte[] doubleToPCM16(double[] s) {
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            short v = (short) Math.max(-32768, Math.min(32767, s[i] * 32767));
            out[2*i]   = (byte)(v & 0xFF);
            out[2*i+1] = (byte)((v >> 8) & 0xFF);
        }
        return out;
    }

    // =========================================================================
    // Yardımcı: 16-bit signed PCM little-endian bayt dizisi → double[] ([-1,1])
    // =========================================================================
    static double[] pcm16ToDouble(byte[] b) {
        int n = b.length / 2;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            short s = (short)((b[2*i+1] << 8) | (b[2*i] & 0xFF));
            out[i] = s / 32768.0;
        }
        return out;
    }
}

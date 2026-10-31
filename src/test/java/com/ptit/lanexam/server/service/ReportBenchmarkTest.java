package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in measurement of the unchanged TCP server. H2/loopback, not a physical LAN benchmark. */
@EnabledIfSystemProperty(named = "lanexam.benchmark", matches = "true")
class ReportBenchmarkTest {
    record Sample(int clients, int repeat, String phase, String code, int operation,
                  double milliseconds, boolean success, String error) {}
    record Phase(int clients, int repeat, String phase, double seconds, int expected, int successful) {}
    final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    final List<Phase> phases = new ArrayList<>();
    record Peer(String code, Socket socket, BufferedReader reader, BufferedWriter writer) implements AutoCloseable {
        static Peer connect(String code, int port) throws IOException {
            Socket socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000); socket.setSoTimeout(20_000);
            return new Peer(code, socket, new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
        }
        void send(MessageType type, Object payload) throws IOException {
            writer.write(JsonUtil.toJson(JsonUtil.message(type, payload))); writer.newLine(); writer.flush();
        }
        NetworkMessage await(MessageType expected) throws IOException {
            for (;;) {
                String line = TransferLimits.readLine(reader); if (line == null) throw new EOFException("Server closed");
                var message = JsonUtil.fromJson(line);
                if (message.getType() == MessageType.ERROR) throw new IOException(message.getPayload().asText());
                if (message.getType() == expected) return message;
            }
        }
        public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    void measure(int n, int repeat, String phase, String code, int operation, Checked action) {
        long start = System.nanoTime(); boolean ok = false; String error = "";
        try { action.run(); ok = true; }
        catch (Exception | AssertionError e) { error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        samples.add(new Sample(n, repeat, phase, code, operation, (System.nanoTime()-start)/1e6, ok, error));
    }
    void phase(int n, int repeat, String name, List<Callable<Void>> tasks, int expected) throws Exception {
        int before = samples.size(); long start = System.nanoTime(); CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = tasks.stream().map(task -> workers.submit(() -> { go.await(); return task.call(); })).toList();
            go.countDown(); for (var job : jobs) job.get(90, TimeUnit.SECONDS);
        }
        int successful = (int)samples.subList(before, samples.size()).stream().filter(Sample::success).count();
        phases.add(new Phase(n, repeat, name, (System.nanoTime()-start)/1e9, expected, successful));
    }
    @Test @Timeout(600) void measureLoginJoinUpdateBarrierAndSubmit() throws Exception {
        Path out = Path.of(System.getProperty("lanexam.benchmark.output", "output/report-final/experiment")); Files.createDirectories(out);
        Instant began = Instant.now();
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:report_"+UUID.randomUUID()+";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("bench", "Benchmark", "Temporary123!");
            var bank = new QuestionBankService(factory::createEntityManager, teacher.id()); var subject = bank.createSubject("Benchmark TCP");
            var classes = new ClassService(factory::createEntityManager, teacher.id()); var group = classes.create("BENCH", "Benchmark", "2026", subject.id());
            StringBuilder csv = new StringBuilder("STT,MSV,Họ và tên,Ngày sinh,Lớp\n");
            for (int i=1;i<=80;i++) csv.append(i).append(",BENCH").append(i).append(",Student ").append(i).append(",12/05/2003,BENCH\n");
            classes.importRoster(group.id(), RosterCsv.parse(csv.toString()));
            var exams = new ExamService(factory::createEntityManager, teacher.id()); var ids = new ArrayList<Long>();
            for (int i=1;i<=30;i++) ids.add(bank.createQuestion(new BankQuestionDraft(subject.id(), (i-1)/5+1,
                    new ExamDraft.QuestionDraft("Benchmark question "+i, QuestionType.MULTIPLE_CHOICE, "Right", "Wrong B", "Wrong C", "Wrong D", "A"))).id());
            var template = exams.createExamFromBank(new BankExamDraft("Benchmark 30 questions", 20, subject.id(), ids));
            var registry = new SessionRegistry(); var server = new ExamServer(registry, new AuthService(factory::createEntityManager, teacher.id()), exams); server.start(0);
            try {
                run(5, 0, server, exams, template.id(), group.id()); // separate warm-up, never part of measured summaries
                samples.clear(); phases.clear();
                for (int n : new int[]{1,10,20,40,80}) for (int repeat=1;repeat<=3;repeat++) run(n, repeat, server, exams, template.id(), group.id());
            } finally { server.stop(); }
        } finally {
            var rows = new ArrayList<String>(); rows.add("clients,repeat,phase,student,operation,milliseconds,success,error");
            for (var s:samples) rows.add(String.format(Locale.ROOT,"%d,%d,%s,%s,%d,%.6f,%s,\"%s\"",s.clients(),s.repeat(),s.phase(),s.code(),s.operation(),s.milliseconds(),s.success(),s.error().replace("\"","\"\"").replace('\n',' ')));
            Files.write(out.resolve("samples.csv"), rows, StandardCharsets.UTF_8);
            rows = new ArrayList<>(); rows.add("clients,repeat,phase,seconds,expected,successful");
            for(var p:phases) rows.add(String.format(Locale.ROOT,"%d,%d,%s,%.6f,%d,%d",p.clients(),p.repeat(),p.phase(),p.seconds(),p.expected(),p.successful()));
            Files.write(out.resolve("phases.csv"),rows,StandardCharsets.UTF_8);
            var meta = new LinkedHashMap<String,Object>(); meta.put("started_utc",began.toString());meta.put("finished_utc",Instant.now().toString());
            meta.put("java",System.getProperty("java.version"));meta.put("os",System.getProperty("os.name"));meta.put("os_version",System.getProperty("os.version"));
            meta.put("processors",Runtime.getRuntime().availableProcessors());meta.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
            meta.put("database","H2 2.3.232 in-memory MODE=MySQL; pool max 20");meta.put("network","TCP 127.0.0.1, ephemeral port; client and server same JVM");
            meta.put("workload","30 questions; one original variant; 3 repetitions; 5 updates per client; 5-client warm-up excluded; virtual-thread raw TCP clients; no JavaFX rendering");
            Files.writeString(out.resolve("environment.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(meta),StandardCharsets.UTF_8);
        }
        assertFalse(samples.isEmpty()); assertTrue(samples.stream().allMatch(Sample::success),"See samples.csv errors");
    }
    void run(int n,int repeat,ExamServer server,ExamService exams,Long template,Long group) throws Exception {
        var round = server.openExam(template,group).exam(); var peers = new ConcurrentHashMap<Integer,Peer>(); var dtos = new ConcurrentHashMap<Integer,ExamDto>();
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for(int i=1;i<=n;i++){int index=i;tasks.add(()->{measure(n,repeat,"LOGIN","BENCH"+index,0,()->{
                var p=Peer.connect("BENCH"+index,server.getPort());peers.put(index,p);p.send(MessageType.LOGIN,new LoginRequest(p.code(),"2003-05-12"));
                assertTrue(JsonUtil.payload(p.await(MessageType.LOGIN_RESULT),LoginResponse.class).isSuccess());
            });return null;});}
            phase(n,repeat,"LOGIN",tasks,n);
            tasks = new ArrayList<>(); for(int i=1;i<=n;i++){int index=i;tasks.add(()->{measure(n,repeat,"JOIN","BENCH"+index,0,()->{
                var p=Objects.requireNonNull(peers.get(index));p.send(MessageType.JOIN_EXAM,new JoinExamRequest(round.getId()));
                var dto=JsonUtil.payload(p.await(MessageType.EXAM_STARTED),ExamDto.class);assertEquals(30,dto.getQuestions().size());dtos.put(index,dto);
            });return null;});} phase(n,repeat,"JOIN",tasks,n);
            tasks = new ArrayList<>(); for(int i=1;i<=n;i++){int index=i;tasks.add(()->{
                var p=peers.get(index);var dto=dtos.get(index);
                for(int op=1;op<=5;op++){int operation=op;measure(n,repeat,"UPDATE_BARRIER","BENCH"+index,op,()->{
                    Map<Long,String> answers=answers(dto);if(operation%2==1)answers.put(dto.getQuestions().getFirst().getId(),"B");
                    p.send(MessageType.ANSWER_UPDATE,new SubmitExamRequest(round.getId(),answers));p.send(MessageType.PING,null);p.await(MessageType.PONG);
                });}return null;});} phase(n,repeat,"UPDATE_BARRIER",tasks,n*5);
            for(var p:peers.values()) assertEquals("B",exams.getOpenAttempt(p.code()).orElseThrow().getSavedAnswers().get(round.getQuestions().getFirst().getId()));
            tasks = new ArrayList<>(); for(int i=1;i<=n;i++){int index=i;tasks.add(()->{measure(n,repeat,"SUBMIT","BENCH"+index,0,()->{
                var p=peers.get(index);p.send(MessageType.SUBMIT_EXAM,new SubmitExamRequest(round.getId(),answers(dtos.get(index))));
                assertEquals(10.0,JsonUtil.payload(p.await(MessageType.SUBMIT_RESULT),SubmitResult.class).getScore());
            });return null;});} phase(n,repeat,"SUBMIT",tasks,n);
        } finally { peers.values().forEach(Peer::close); server.endExam(round.getId()); }
    }
    static Map<Long,String> answers(ExamDto dto){var result=new LinkedHashMap<Long,String>();dto.getQuestions().forEach(q->result.put(q.getId(),"A"));return result;}
}

package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "questions")
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    @Column(name = "option_a", length = 500)
    private String optionA;
    @Column(name = "option_b", length = 500)
    private String optionB;
    @Column(name = "option_c", length = 500)
    private String optionC;
    @Column(name = "option_d", length = 500)
    private String optionD;

    @Column(name = "correct_answer", nullable = false, length = 500)
    private String correctAnswer;

    // Nullable metadata keeps existing exams compatible; question text/answers are immutable exam snapshots.
    @Column(name = "bank_question_id")
    private Long bankQuestionId;
    private Integer difficulty;

    public Question() {}

    public Question(String content, QuestionType type, String optionA, String optionB,
                    String optionC, String optionD, String correctAnswer) {
        this.content = content;
        this.type = type;
        this.optionA = optionA;
        this.optionB = optionB;
        this.optionC = optionC;
        this.optionD = optionD;
        this.correctAnswer = correctAnswer;
    }

    public Long getId() { return id; }
    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
    public String getContent() { return content; }
    public QuestionType getType() { return type; }
    public String getOptionA() { return optionA; }
    public String getOptionB() { return optionB; }
    public String getOptionC() { return optionC; }
    public String getOptionD() { return optionD; }
    public String getCorrectAnswer() { return correctAnswer; }
    public Long getBankQuestionId() { return bankQuestionId; }
    public Integer getDifficulty() { return difficulty; }

    public static Question fromBank(BankQuestion source) {
        Question snapshot = new Question(source.getContent(), source.getType(), source.getOptionA(), source.getOptionB(),
                source.getOptionC(), source.getOptionD(), source.getCorrectAnswer());
        snapshot.bankQuestionId = source.getId();
        snapshot.difficulty = source.getDifficulty();
        return snapshot;
    }

    public Question copyForRound() {
        Question copy = new Question(content, type, optionA, optionB, optionC, optionD, correctAnswer);
        copy.bankQuestionId = bankQuestionId;
        copy.difficulty = difficulty;
        return copy;
    }
}

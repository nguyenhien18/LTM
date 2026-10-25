package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;
import java.util.Locale;

@Entity
@Table(name = "bank_questions", indexes = @Index(name = "idx_bank_subject_difficulty_type", columnList = "subject_id,difficulty,type"))
public class BankQuestion extends TeacherOwned {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;
    @Column(nullable = false)
    private int difficulty;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
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

    public BankQuestion() {}
    public void update(Subject subject, int difficulty, String content, QuestionType type,
                       String optionA, String optionB, String optionC, String optionD, String correctAnswer) {
        this.subject = subject;
        this.difficulty = difficulty;
        this.content = content.trim();
        this.type = type;
        boolean choice = type == QuestionType.MULTIPLE_CHOICE;
        this.optionA = choice ? optionA.trim() : null;
        this.optionB = choice ? optionB.trim() : null;
        this.optionC = choice ? optionC.trim() : null;
        this.optionD = choice ? optionD.trim() : null;
        this.correctAnswer = choice ? correctAnswer.trim().toUpperCase(Locale.ROOT) : correctAnswer.trim();
    }

    public Long getId() { return id; }
    public Subject getSubject() { return subject; }
    public int getDifficulty() { return difficulty; }
    public String getContent() { return content; }
    public QuestionType getType() { return type; }
    public String getOptionA() { return optionA; }
    public String getOptionB() { return optionB; }
    public String getOptionC() { return optionC; }
    public String getOptionD() { return optionD; }
    public String getCorrectAnswer() { return correctAnswer; }
}

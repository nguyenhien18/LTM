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
}

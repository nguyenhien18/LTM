package com.ptit.lanexam.server.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "student_answers")
public class StudentAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "answer_text", length = 1000)
    private String answerText;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    public StudentAnswer() {}
    public StudentAnswer(Question question, String answerText, boolean correct) {
        this.question = question;
        this.answerText = answerText;
        this.correct = correct;
    }

    public void setSubmission(Submission submission) { this.submission = submission; }
    public boolean isCorrect() { return correct; }
}

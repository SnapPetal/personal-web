package biz.thonbecker.personal.budget.platform.document;

public class BudgetDocumentException extends RuntimeException {

    public BudgetDocumentException(final String message) {
        super(message);
    }

    public BudgetDocumentException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

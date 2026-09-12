package biz.thonbecker.personal.foosball.platform.web.model;

public record GameRequest(
        Long whiteTeamPlayer1Id,
        Long whiteTeamPlayer2Id,
        Long blackTeamPlayer1Id,
        Long blackTeamPlayer2Id,
        int whiteTeamScore,
        int blackTeamScore) {}

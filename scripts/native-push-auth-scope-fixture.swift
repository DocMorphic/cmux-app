import Foundation

// Inert coordinator storage seam. The tested getters/stream owner, session
// identity and team scope below are the actual pinned source, not copied logic.
public struct FixtureSessionCache { var hasTokens = true }
public struct FixtureUser { var id = "fixture-account" }
public struct FixtureTeam { var id: String }
@MainActor public final class AuthCoordinator {
    var sessionCache = FixtureSessionCache()
    var currentUser: FixtureUser? = FixtureUser()
    var selectedTeamID: String? = "cached-team"
    var publishedAuthenticatedSessionIdentity: AuthenticatedSessionIdentity?
    var authenticatedTeamsSessionGeneration: UInt64?
    var resolvedTeamID: String?
    var availableTeams: [FixtureTeam] = []
    var authenticatedTeamScopeGeneration: UInt64 = 0
    var authenticatedTeamScopeContinuations: [UUID: AsyncStream<AuthenticatedTeamScope?>.Continuation] = [:]
    var lastPublishedAuthenticatedTeamScope: AuthenticatedTeamScope?
}

@main struct NativePushAuthScopeFixture {
    @MainActor static func main() throws {
        let owner = AuthCoordinator()
        var checks = 0
        func expect(_ condition: Bool) throws {
            guard condition else { throw NSError(domain: "Native team authority fixture failed", code: checks) }
            checks += 1
        }
        try expect(owner.cachedTeamIdentity != nil && !owner.hasAuthenticatedTeamResolution)
        owner.publishedAuthenticatedSessionIdentity = .init(generation: 7, accountID: "fixture-account")
        try expect(!owner.hasAuthenticatedTeamResolution)
        owner.authenticatedTeamsSessionGeneration = 6
        try expect(!owner.hasAuthenticatedTeamResolution)
        owner.authenticatedTeamsSessionGeneration = 7
        try expect(owner.hasAuthenticatedTeamResolution && owner.authenticatedTeamScope == nil)
        owner.availableTeams = [.init(id: "fixture-team")]
        owner.resolvedTeamID = "fixture-team"
        try expect(owner.hasAuthenticatedTeamResolution && owner.authenticatedTeamScope?.teamID == "fixture-team")
        owner.resolvedTeamID = "unverified-team"
        try expect(!owner.hasAuthenticatedTeamResolution)
        owner.resolvedTeamID = "fixture-team"
        owner.publishedAuthenticatedSessionIdentity = .init(generation: 8, accountID: "fixture-account")
        try expect(!owner.hasAuthenticatedTeamResolution)
        owner.publishedAuthenticatedSessionIdentity = nil
        try expect(!owner.hasAuthenticatedTeamResolution)
        print("Native auth team authority: \(checks) cached/unresolved/generation/membership/teamless/sign-out cases passed")
    }
}

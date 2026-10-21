import Foundation

struct SourceMessage { let id: String; let replyToID: String?; var replyCount: Int = 9 }
enum Footer: String { case none, notDelivered, other }
struct MessageRowModel {
    var message: SourceMessage
    let isOutgoing: Bool
    let reservesAvatarColumn: Bool
    var showsAvatar = false
    var showsSenderName = false
    var showsTail = false
    var replyQuote: String? = "source quote"
    var footer: Footer = .other
}
enum SourceRow { case message(MessageRowModel), other }
struct SourceStore {
    let rows: [SourceRow]
    var messages: [SourceMessage] { rows.compactMap { if case let .message(model) = $0 { return model.message }; return nil } }
    func message(id: String) -> SourceMessage? { messages.first { $0.id == id } }
}
enum ConversationRowBuilder { static func rows(store: SourceStore) -> [SourceRow] { store.rows } }

@main enum Fixture {
    static func main() throws {
        let rows: [(String,String?,Bool,Footer)] = [("root",nil,false,.other),("mine","root",true,.other),
            ("other",nil,false,.other),("child","root",false,.other),("failed","root",true,.notDelivered),("newest","root",true,.other)]
        var results: [[String:Any]] = []
        for root in ["root","other","mine","missing"] {
            let models = rows.map { MessageRowModel(message: SourceMessage(id:$0.0,replyToID:$0.1),isOutgoing:$0.2,reservesAvatarColumn:false,footer:$0.3) }
            let store = SourceStore(rows: [.other] + models.map { .message($0) })
            var copied: [MessageRowModel]?
            sourceOpen(store:store,rootID:root,result:&copied)
            results.append(["root":root,"input":rows.map { ["id":$0.0,"replyTo":$0.1 as Any? ?? NSNull(),"outgoing":$0.2,"footer":$0.3.rawValue] },
                "opened":copied != nil,"rows":(copied ?? []).map { ["id":$0.message.id,"replyQuoteRemoved":$0.replyQuote==nil,
                    "replyCount":$0.message.replyCount,"tail":$0.showsTail,"senderName":$0.showsSenderName,"avatar":$0.showsAvatar,"footer":$0.footer.rawValue] }])
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":results],options:[.prettyPrinted,.sortedKeys]))
    }
}

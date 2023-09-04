// Import Nylas packages
import com.nylas.NylasClient
import com.nylas.models.FindEventQueryParams
import com.nylas.models.When

// Import Spark and Jackson packages
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

// Import Spark packages
import spark.ModelAndView
import spark.kotlin.Http
import spark.kotlin.ignite
import spark.template.mustache.MustacheTemplateEngine;

// Import Java libraries
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder
import java.text.SimpleDateFormat

// Kotlin Data Class to hold Webhook information
data class Webhook_Info(
    var id: String,
    var date: String,
    var title: String,
    var description: String,
    var participants: String,
    var status: String
)

// Array of Data classes
var array: Array<Webhook_Info> = arrayOf()

// Object and function to get Hmac
object Hmac {
    fun digest(
        msg: String,
        key: String,
        alg: String = "HmacSHA256"
    ): String {
        val signingKey = SecretKeySpec(key.toByteArray(), alg)
        val mac = Mac.getInstance(alg)
        mac.init(signingKey)

        val bytes = mac.doFinal(msg.toByteArray())
        return format(bytes)
    }

    private fun format(bytes: ByteArray): String {
        val formatter = Formatter()
        bytes.forEach { formatter.format("%02x", it) }
        return formatter.toString()
    }
}

// Function to add elements to an array
fun addElement(arr: Array<Webhook_Info>, element: Webhook_Info): Array<Webhook_Info> {
    val mutableArray = arr.toMutableList()
    mutableArray.add(element)
    return mutableArray.toTypedArray()
}

// Function to format the date
fun dateFormatter(milliseconds: String): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date(milliseconds.toLong() * 1000)).toString()
}

fun main(args: Array<String>) {
    // Start our Spark server
    val http: Http = ignite()
    // Initialize Nylas client
    val nylas: NylasClient = NylasClient(
        apiKey = System.getenv("V3_TOKEN"),
        apiUri = System.getenv("BASE_URL"),
    )

    // Validate our webhook with the Nylas server
    http.get("/webhook") {
        request.queryParams("challenge")
    }

    // Getting webhook information
    http.post("/webhook") {
        // Create Json object mapper
        val mapper = jacksonObjectMapper()
        // Read the response body as a Json object
        val model: JsonNode = mapper.readValue<JsonNode>(request.body())
        // Make sure we reading our calendar
        if(model["data"]["object"]["calendar_id"].textValue().equals(System.getenv("CALENDAR_ID"), false)){
            // Make sure the webhook is coming from Nylas
            if(Hmac.digest(request.body(), URLEncoder.encode(System.getenv("CLIENT_SECRET"), "UTF-8")) == request.headers("X-Nylas-Signature").toString()){
                // Read the event information using the events ednpoint
                val eventquery = FindEventQueryParams(System.getenv("CALENDAR_ID"))
                val myevent = nylas.events().find(System.getenv("GRANT_ID"), eventId = model["data"]["object"]["id"].textValue(), queryParams = eventquery)
                // Read the participants and put them into a single line
                var participants: String = ""
                for (participant in myevent.data.participants){
                    participants = "$participants;${participant.email.toString()}"
                }
                // Get the time of the event. It can be a full day event or have a start and end time
                var event_datetime: String = ""
                when(myevent.data.getWhen().getObject().toString()) {
                    "DATESPAN" -> {
                        val datespan = myevent.data.getWhen() as When.Datespan
                        event_datetime = datespan.startDate.toString()
                    }
                    "TIMESPAN" -> {
                        val timespan = myevent.data.getWhen() as When.Timespan
                        val startDate = dateFormatter(timespan.startTime.toString())
                        val endDate = dateFormatter(timespan.endTime.toString())
                        event_datetime = "From $startDate to $endDate"
                    }
                }
                // Remove the first ";"
                participants = participants.drop(1)
                // Add webhook call to an array, so that we display it on screen
                array = addElement(array, Webhook_Info(myevent.data.id, event_datetime.toString(), myevent.data.title.toString(),
                    myevent.data.description.toString(), participants, myevent.data.status.toString()))
            }
        }
        ""
    }

    http.get("/") {
        // Display webhook information using a Mustache template
        val model = HashMap<String, Any>()
        model["webhooks"] = array
        MustacheTemplateEngine().render(
            ModelAndView(model, "show_webhooks.mustache")
        )
    }
}
